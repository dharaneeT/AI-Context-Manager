package com.contextlayer.backend.summary;

import com.contextlayer.backend.common.BadRequestException;
import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.config.LlmProperties;
import com.contextlayer.backend.indexing.ProjectFile;
import com.contextlayer.backend.indexing.ProjectFileRepository;
import com.contextlayer.backend.llm.LlmException;
import com.contextlayer.backend.project.Project;
import com.contextlayer.backend.project.ProjectRepository;
import com.contextlayer.backend.symbols.CodeSymbol;
import com.contextlayer.backend.symbols.CodeSymbolRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class SummaryService {

	private static final int MAX_CONSECUTIVE_FAILURES = 3;

	private final ProjectRepository projectRepository;
	private final ProjectFileRepository fileRepository;
	private final CodeSymbolRepository symbolRepository;
	private final FileSummaryRepository summaryRepository;
	private final LlmProperties llmProps;
	private final Map<String, SummaryGenerator> generators = new LinkedHashMap<>();
	private final String defaultGenerator;

	public SummaryService(
		ProjectRepository projectRepository,
		ProjectFileRepository fileRepository,
		CodeSymbolRepository symbolRepository,
		FileSummaryRepository summaryRepository,
		LlmProperties llmProps,
		List<SummaryGenerator> all,
		@Value("${contextlayer.summary.default-generator:heuristic}") String defaultGenerator
	) {
		this.projectRepository = projectRepository;
		this.fileRepository = fileRepository;
		this.symbolRepository = symbolRepository;
		this.summaryRepository = summaryRepository;
		this.llmProps = llmProps;
		this.defaultGenerator = defaultGenerator;
		all.forEach(g -> generators.put(g.name(), g));
	}

	public SummaryRefreshResult refresh(Long projectId, boolean force) {
		return refresh(projectId, force, null);
	}

	/**
	 * NOT @Transactional on purpose: local LLM calls are slow, and we don't want a DB connection held
	 * hostage meanwhile. Each save commits by itself, so a failure halfway keeps everything done so far.
	 */
	public SummaryRefreshResult refresh(Long projectId, boolean force, String generatorName) {
		Project project = projectRepository
			.findById(projectId)
			.orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
		SummaryGenerator generator = resolve(generatorName);
		if (!generator.available()) {
			throw new LlmException(
				"The '" +
				generator.name() +
				"' summary generator needs Ollama running at " +
				llmProps.baseUrl() +
				". Start Ollama and run: ollama pull " +
				llmProps.summaryModel(),
				true
			);
		}
		Path root = Path.of(project.getRootPath());

		Map<Long, FileSummary> existing = new HashMap<>();
		summaryRepository.findAllByProjectId(projectId).forEach(s -> existing.put(s.getFile().getId(), s));
		Map<Long, List<CodeSymbol>> symbolsByFile = symbolRepository
			.findAllByProjectId(projectId)
			.stream()
			.collect(Collectors.groupingBy(s -> s.getFile().getId()));

		int generated = 0, unchanged = 0, skipped = 0, failed = 0, consecutiveFailures = 0;
		String note = null;
		Instant now = Instant.now();

		for (ProjectFile file : fileRepository.findAllByProjectId(projectId)) {
			FileSummary current = existing.get(file.getId());
			int existingQuality = current == null || !generators.containsKey(current.getGenerator())
				? 0
				: generators.get(current.getGenerator()).quality();
			if (
				!force &&
				current != null &&
				isFresh(
					current.getSourceHash(),
					current.getGenerator(),
					existingQuality,
					file.getContentHash(),
					generator
				)
			) {
				unchanged++;
				continue;
			}
			if (generator.quality() > 0 && generated >= llmProps.maxSummaryFilesPerRun()) {
				note =
					"Stopped at the per-run limit of " +
					llmProps.maxSummaryFilesPerRun() +
					" files (a local model is slow). Run again to continue.";
				break;
			}

			String content = read(root, file.getRelativePath());
			SummaryInput input = new SummaryInput(
				file.getRelativePath(),
				file.getLanguage(),
				file.getLineCount(),
				symbolsByFile.getOrDefault(file.getId(), List.of()),
				content
			);
			if (!generator.supports(input)) {
				skipped++; // e.g. config files never go to the LLM
				continue;
			}

			String text;
			try {
				text = generator.summarize(input);
				consecutiveFailures = 0;
			} catch (LlmException e) {
				failed++;
				if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
					note = "Stopped after " + MAX_CONSECUTIVE_FAILURES + " consecutive LLM errors: " + e.getMessage();
					break;
				}
				continue;
			}

			FileSummary row = current != null ? current : new FileSummary();
			row.setFile(file);
			row.setSummary(text);
			row.setSourceHash(file.getContentHash());
			row.setGenerator(generator.name());
			row.setGeneratedAt(now);
			summaryRepository.save(row);
			generated++;
		}
		log.info(
			"Summaries for project {} ({}): {} generated, {} unchanged, {} skipped, {} failed",
			projectId,
			generator.name(),
			generated,
			unchanged,
			skipped,
			failed
		);
		return new SummaryRefreshResult(generated, unchanged, skipped, failed, generator.name(), note);
	}

	/** Fresh = same content, and written by this generator or by a better one. */
	static boolean isFresh(
		String existingHash,
		String existingGenerator,
		int existingQuality,
		String fileHash,
		SummaryGenerator chosen
	) {
		if (existingHash == null || !existingHash.equals(fileHash)) {
			return false;
		}
		if (existingQuality > chosen.quality()) {
			return true;
		}
		return existingQuality == chosen.quality() && chosen.name().equals(existingGenerator);
	}

	@Transactional(readOnly = true)
	public FileSummary get(Long projectId, String path) {
		return summaryRepository
			.findByPath(projectId, path)
			.orElseThrow(() -> new NotFoundException("No summary for " + path + ". Run a summary refresh first."));
	}

	private SummaryGenerator resolve(String name) {
		String wanted = name == null || name.isBlank() ? defaultGenerator : name.strip();
		SummaryGenerator generator = generators.get(wanted);
		if (generator == null) {
			throw new BadRequestException(
				"Unknown summary generator '" + wanted + "'. Available: " + String.join(", ", generators.keySet())
			);
		}
		return generator;
	}

	private static String read(Path root, String relative) {
		try {
			return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "";
		}
	}
}
