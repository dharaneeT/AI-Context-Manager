package com.contextlayer.backend.pipeline;

import com.contextlayer.backend.common.BadRequestException;
import com.contextlayer.backend.git.GitException;
import com.contextlayer.backend.git.GitIntelligenceService;
import com.contextlayer.backend.git.GitResponses.SyncResult;
import com.contextlayer.backend.graph.DependencyService;
import com.contextlayer.backend.graph.GraphBuildResult;
import com.contextlayer.backend.indexing.IndexResult;
import com.contextlayer.backend.indexing.IndexingService;
import com.contextlayer.backend.summary.SummaryRefreshResult;
import com.contextlayer.backend.summary.SummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Runs every step that keeps a project's memory up to date, in dependency order. Each step has its own transaction. */
@Service
@RequiredArgsConstructor
public class ProjectPipelineService {

	private final IndexingService indexingService;
	private final GitIntelligenceService gitService;
	private final DependencyService dependencyService;
	private final SummaryService summaryService;

	public RefreshReport refreshAll(Long projectId, boolean force) {
		IndexResult index = indexingService.indexProject(projectId, force); // files, chunks, symbols

		SyncResult git = null;
		String gitNote = null;
		try {
			git = gitService.sync(projectId);
		} catch (BadRequestException | GitException e) {
			gitNote = e.getMessage(); // not a git repo, or git missing: everything else still works
		}

		GraphBuildResult graph = dependencyService.build(projectId); // needs symbols
		SummaryRefreshResult summaries = summaryService.refresh(projectId, force); // needs symbols
		return new RefreshReport(index, git, gitNote, graph, summaries);
	}
}
