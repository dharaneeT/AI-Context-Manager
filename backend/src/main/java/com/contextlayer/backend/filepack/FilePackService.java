package com.contextlayer.backend.filepack;

import com.contextlayer.backend.budget.TokenCounter;
import com.contextlayer.backend.common.BadRequestException;
import com.contextlayer.backend.filepack.FilePackModel.Category;
import com.contextlayer.backend.filepack.FilePackModel.DiscoverRequest;
import com.contextlayer.backend.filepack.FilePackModel.DiscoverResponse;
import com.contextlayer.backend.filepack.FilePackModel.ExcludedFile;
import com.contextlayer.backend.filepack.FilePackModel.ExportRequest;
import com.contextlayer.backend.filepack.FilePackModel.ExportResult;
import com.contextlayer.backend.filepack.FilePackModel.FilePreview;
import com.contextlayer.backend.filepack.FilePackModel.RecommendedFile;
import com.contextlayer.backend.filepack.FilePackModel.Totals;
import com.contextlayer.backend.graph.DependencyGraph;
import com.contextlayer.backend.graph.DependencyService;
import com.contextlayer.backend.indexing.ProjectFile;
import com.contextlayer.backend.indexing.ProjectFileRepository;
import com.contextlayer.backend.project.Project;
import com.contextlayer.backend.project.ProjectService;
import com.contextlayer.backend.ranking.RankingResult.Ranking;
import com.contextlayer.backend.ranking.RankingService;
import com.contextlayer.backend.security.SecretRedactor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Task File Pack. Reuses RankingService (hybrid search + graph + git signals) and DependencyService, and never
 * writes to the project: it only reads files and produces a temporary ZIP.
 */
@Service
@RequiredArgsConstructor
public class FilePackService {

	static final String DISCLAIMER =
		"This is a recommended selection, not a guarantee that it contains every file needed for the task. Please review it.";

	private static final int MAX_TASK_CHARS = 4000;
	private static final int COUNT_EXACTLY_UP_TO_CHARS = 200_000;
	private static final DateTimeFormatter STAMP = DateTimeFormatter
		.ofPattern("yyyyMMdd-HHmmss")
		.withZone(ZoneOffset.UTC);

	private final ProjectService projectService;
	private final RankingService rankingService;
	private final DependencyService dependencyService;
	private final ProjectFileRepository fileRepository;
	private final TokenCounter tokens;
	private final FilePackProperties props;

	/** Step 1: which files look relevant to this task? Does not read file contents. */
	public DiscoverResponse discover(Long projectId, DiscoverRequest request) {
		projectService.getEntity(projectId); // 404 for an unknown project
		return discover(projectId, request.task(), request.maxFiles());
	}

	private DiscoverResponse discover(Long projectId, String rawTask, Integer maxFiles) {
		String task = requireTask(rawTask);
		int limit = Math.min(Math.max(maxFiles == null ? props.discoverFiles() : maxFiles, 1), props.maxFiles());

		Map<String, Long> indexed = indexedSizes(projectId);
		if (indexed.isEmpty()) {
			throw new BadRequestException("This project has no indexed files yet. Index the project first.");
		}
		Ranking ranking = rankingService.rank(projectId, task, limit);
		DependencyGraph graph = dependencyService.loadGraph(projectId);
		FilePackPlanner.Plan plan = FilePackPlanner.plan(
			ranking.files(),
			graph,
			indexed,
			new FilePackPlanner.Settings(
				props.maxFiles(),
				props.coreFiles(),
				props.expandTopFiles(),
				props.maxExtraDependencies()
			)
		);

		List<String> notes = new ArrayList<>();
		if (!ranking.semanticUsed() && ranking.semanticNote() != null) {
			notes.add("Keyword-only ranking: " + ranking.semanticNote());
		}
		if (plan.hiddenSensitive() > 0) {
			notes.add(plan.hiddenSensitive() + " file(s) were hidden because they look like secrets or credentials.");
		}
		if (plan.files().isEmpty()) {
			notes.add("No relevant files were found. Try describing the task with more specific words.");
		}
		long bytes = plan.files().stream().mapToLong(RecommendedFile::sizeBytes).sum();
		long estimatedTokens = plan.files().stream().mapToLong(RecommendedFile::estimatedTokens).sum();
		return new DiscoverResponse(
			task,
			ranking.semanticUsed(),
			DISCLAIMER,
			plan.files(),
			new Totals(plan.files().size(), bytes, estimatedTokens),
			notes
		);
	}

	/** Step 2 (optional): show one file before exporting. Secrets are masked in the preview. */
	public FilePreview preview(Long projectId, String path) {
		Project project = projectService.getEntity(projectId);
		String relative = SafeProjectFiles.requireRelative(path);
		requireIndexed(projectId, relative);
		Path root = SafeProjectFiles.realRoot(project.getRootPath());
		byte[] bytes = SafeProjectFiles.read(root, relative, props.maxFileSizeBytes());
		String text = new String(bytes, StandardCharsets.UTF_8);
		SecretRedactor.Result redacted = SecretRedactor.redact(text);
		return new FilePreview(relative, bytes.length, countTokens(text), redacted.redactions(), redacted.text());
	}

	/** Step 3: build the ZIP in a temporary file. The caller must delete result.file() after sending it. */
	public ExportResult export(Long projectId, ExportRequest request) {
		Project project = projectService.getEntity(projectId);
		String task = requireTask(request.task());

		if (request.paths() == null || request.paths().isEmpty()) {
			throw new BadRequestException("Select at least one file to export.");
		}
		if (request.paths().size() > props.maxFiles()) {
			throw new BadRequestException("Too many files selected (limit " + props.maxFiles() + ").");
		}

		// Never trust the browser: check every path ourselves before touching the disk.
		Set<String> indexed = indexedSizes(projectId).keySet();
		Set<String> selected = new LinkedHashSet<>(); // removes duplicates, keeps order
		for (String raw : request.paths()) {
			String path = SafeProjectFiles.requireRelative(raw);
			String reason = SafeProjectFiles.blockedReason(path);
			if (reason != null) {
				throw new BadRequestException("This file cannot be exported because " + reason + ": " + path);
			}
			if (!indexed.contains(path)) {
				throw new BadRequestException("File is not part of the indexed project: " + path);
			}
			selected.add(path);
		}

		// Scores, categories and reasons come from our own ranking, not from the request.
		Map<String, RecommendedFile> known = new HashMap<>();
		discover(projectId, task, props.maxFiles()).files().forEach(f -> known.put(f.path(), f));

		Path root = SafeProjectFiles.realRoot(project.getRootPath());
		Instant exportedAt = Instant.now();
		List<ExcludedFile> excluded = new ArrayList<>();
		Path temp = null;
		boolean success = false;
		try {
			temp = Files.createTempFile("contextlayer-filepack-", ".zip");
			int included;
			try (FilePackZipWriter zip = new FilePackZipWriter(temp)) {
				long total = 0;
				for (String path : selected) {
					byte[] bytes = SafeProjectFiles.read(root, path, props.maxFileSizeBytes()); // re-checks path and size
					total += bytes.length;
					if (total > props.maxTotalBytes()) {
						throw new BadRequestException(
							"The selection is too large (limit " +
							(props.maxTotalBytes() / (1024 * 1024)) +
							" MB). Deselect some files."
						);
					}
					String text = new String(bytes, StandardCharsets.UTF_8);
					if (SecretRedactor.redact(text).redactions() > 0) {
						// The file is never changed, so a file with a possible secret is left out instead.
						excluded.add(new ExcludedFile(path, "possible secret detected, so the file was left out"));
						continue;
					}
					RecommendedFile meta = known.get(path);
					zip.addFile(
						new FilePackZipWriter.Item(
							path,
							meta == null ? Category.CONTEXT_ONLY : meta.category(),
							meta == null ? 0.0 : meta.score(),
							meta == null ? "Added manually (not in the recommended list)" : meta.reason(),
							bytes.length,
							countTokens(text)
						),
						bytes
					);
				}
				included = zip.count();
				if (included == 0) {
					throw new BadRequestException(
						"None of the selected files could be exported (possible secrets were found)."
					);
				}
				zip.finish(task, exportedAt, excluded);
			}
			ExportResult result = new ExportResult(
				temp,
				fileName(project, exportedAt),
				Files.size(temp),
				included,
				excluded.size()
			);
			success = true;
			return result;
		} catch (IOException e) {
			throw new UncheckedIOException("Could not create the archive.", e);
		} finally {
			if (!success && temp != null) {
				try {
					Files.deleteIfExists(temp);
				} catch (IOException ignored) {
					// nothing more we can do
				}
			}
		}
	}

	private Map<String, Long> indexedSizes(Long projectId) {
		Map<String, Long> sizes = new HashMap<>();
		for (ProjectFile f : fileRepository.findAllByProjectId(projectId)) {
			sizes.put(f.getRelativePath(), f.getSizeBytes());
		}
		return sizes;
	}

	private void requireIndexed(Long projectId, String relative) {
		if (!indexedSizes(projectId).containsKey(relative)) {
			throw new BadRequestException("File is not part of the indexed project: " + relative);
		}
	}

	private static String requireTask(String task) {
		if (task == null || task.isBlank()) {
			throw new BadRequestException("task must not be blank");
		}
		if (task.length() > MAX_TASK_CHARS) {
			throw new BadRequestException("task is too long (limit " + MAX_TASK_CHARS + " characters)");
		}
		return task.strip();
	}

	private int countTokens(String text) {
		return text.length() > COUNT_EXACTLY_UP_TO_CHARS ? text.length() / 4 : tokens.count(text);
	}

	private static String fileName(Project project, Instant at) {
		String name = project.getName() == null ? "project" : project.getName().replaceAll("[^A-Za-z0-9._-]+", "-");
		return "file-pack-" + (name.isBlank() ? "project" : name) + "-" + STAMP.format(at) + ".zip";
	}
}
