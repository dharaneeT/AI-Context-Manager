package com.contextlayer.backend.summary;

import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.indexing.ProjectFile;
import com.contextlayer.backend.indexing.ProjectFileRepository;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryService {

	private final ProjectRepository projectRepository;
	private final ProjectFileRepository fileRepository;
	private final CodeSymbolRepository symbolRepository;
	private final FileSummaryRepository summaryRepository;
	private final SummaryGenerator generator;

	/** Regenerates summaries only for files that are new, changed, or were made by a different generator. */
	@Transactional
	public SummaryRefreshResult refresh(Long projectId, boolean force) {
		Project project = projectRepository
			.findById(projectId)
			.orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
		Path root = Path.of(project.getRootPath());

		Map<Long, FileSummary> existing = new HashMap<>();
		summaryRepository.findAllByProjectId(projectId).forEach(s -> existing.put(s.getFile().getId(), s));

		Map<Long, List<CodeSymbol>> symbolsByFile = symbolRepository
			.findAllByProjectId(projectId)
			.stream()
			.collect(Collectors.groupingBy(s -> s.getFile().getId()));

		int generated = 0;
		int unchanged = 0;
		Instant now = Instant.now();

		for (ProjectFile file : fileRepository.findAllByProjectId(projectId)) {
			FileSummary current = existing.get(file.getId());
			boolean fresh =
				current != null &&
				current.getSourceHash().equals(file.getContentHash()) &&
				current.getGenerator().equals(generator.name());
			if (fresh && !force) {
				unchanged++;
				continue;
			}

			String content = "";
			try {
				content = new String(Files.readAllBytes(root.resolve(file.getRelativePath())), StandardCharsets.UTF_8);
			} catch (IOException e) {
				log.debug("No content for {}: {}", file.getRelativePath(), e.getMessage());
			}

			String text = generator.summarize(
				new SummaryInput(
					file.getRelativePath(),
					file.getLanguage(),
					file.getLineCount(),
					symbolsByFile.getOrDefault(file.getId(), List.of()),
					content
				)
			);

			FileSummary row = current != null ? current : new FileSummary();
			row.setFile(file);
			row.setSummary(text);
			row.setSourceHash(file.getContentHash());
			row.setGenerator(generator.name());
			row.setGeneratedAt(now);
			summaryRepository.save(row);
			generated++;
		}
		log.info("Summaries for project {}: {} generated, {} unchanged", projectId, generated, unchanged);
		return new SummaryRefreshResult(generated, unchanged, generator.name());
	}

	@Transactional(readOnly = true)
	public FileSummary get(Long projectId, String path) {
		return summaryRepository
			.findByPath(projectId, path)
			.orElseThrow(() -> new NotFoundException("No summary for " + path + ". Run a summary refresh first."));
	}
}
