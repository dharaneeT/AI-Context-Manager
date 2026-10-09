package com.contextlayer.backend.summary;

import com.contextlayer.backend.git.GitIntelligenceService;
import com.contextlayer.backend.graph.DependencyService;
import com.contextlayer.backend.graph.FileDegree;
import com.contextlayer.backend.indexing.IndexingService;
import com.contextlayer.backend.indexing.ProjectFileRepository;
import com.contextlayer.backend.indexing.ProjectStats;
import com.contextlayer.backend.project.ProjectService;
import com.contextlayer.backend.symbols.CodeSymbolRepository;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectOverviewService {

	private final ProjectService projectService;
	private final IndexingService indexingService;
	private final ProjectFileRepository fileRepository;
	private final CodeSymbolRepository symbolRepository;
	private final DependencyService dependencyService;
	private final GitIntelligenceService gitService;

	public ProjectOverview overview(Long projectId) {
		String name = projectService.get(projectId).name();
		ProjectStats stats = indexingService.stats(projectId);

		Map<String, Long> byFolder = fileRepository
			.findAllPaths(projectId)
			.stream()
			.collect(
				Collectors.groupingBy(
					p -> p.contains("/") ? p.substring(0, p.indexOf('/')) : "(root)",
					TreeMap::new,
					Collectors.counting()
				)
			);
		List<String> topFolders = byFolder
			.entrySet()
			.stream()
			.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
			.limit(8)
			.map(e -> e.getKey() + " (" + e.getValue() + ")")
			.toList();

		List<String> entryPoints = symbolRepository.findEntryPointPaths(projectId).stream().limit(5).toList();
		List<String> hubs = dependencyService.mostDependedOn(projectId, 5).stream().map(FileDegree::path).toList();
		List<String> active = gitService
			.hotFiles(projectId, 5)
			.stream()
			.filter(h -> h.commitsLast30Days() > 0)
			.map(h -> h.path())
			.toList();

		return new ProjectOverview(
			name,
			stats.files(),
			stats.estimatedTokens(),
			stats.languages(),
			topFolders,
			entryPoints,
			hubs,
			active
		);
	}
}
