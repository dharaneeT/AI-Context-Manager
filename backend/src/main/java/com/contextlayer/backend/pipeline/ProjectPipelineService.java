package com.contextlayer.backend.pipeline;

import com.contextlayer.backend.common.BadRequestException;
import com.contextlayer.backend.embedding.EmbeddingException;
import com.contextlayer.backend.embedding.EmbeddingService;
import com.contextlayer.backend.embedding.EmbeddingService.EmbedResult;
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

@Service
@RequiredArgsConstructor
public class ProjectPipelineService {

	private final IndexingService indexingService;
	private final GitIntelligenceService gitService;
	private final DependencyService dependencyService;
	private final SummaryService summaryService;
	private final EmbeddingService embeddingService;

	public RefreshReport refreshAll(Long projectId, boolean force) {
		IndexResult index = indexingService.indexProject(projectId, force);

		SyncResult git = null;
		String gitNote = null;
		try {
			git = gitService.sync(projectId);
		} catch (BadRequestException | GitException e) {
			gitNote = e.getMessage();
		}

		GraphBuildResult graph = dependencyService.build(projectId);
		SummaryRefreshResult summaries = summaryService.refresh(projectId, force);

		EmbedResult embeddings = null;
		String embeddingNote = null;
		try {
			embeddings = embeddingService.refresh(projectId);
		} catch (EmbeddingException e) {
			embeddingNote = e.getMessage(); // no pgvector or Ollama down: everything else still works
		}
		return new RefreshReport(index, git, gitNote, graph, summaries, embeddings, embeddingNote);
	}
}
