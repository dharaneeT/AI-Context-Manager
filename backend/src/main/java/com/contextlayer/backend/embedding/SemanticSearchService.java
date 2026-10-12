package com.contextlayer.backend.embedding;

import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.config.EmbeddingProperties;
import com.contextlayer.backend.project.ProjectRepository;
import com.contextlayer.backend.search.SearchResults.ChunkHit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SemanticSearchService {

	public record SemanticResult(String model, List<ChunkHit> chunks) {}

	private final VectorStore store;
	private final EmbeddingProvider provider;
	private final EmbeddingProperties props;
	private final ProjectRepository projectRepository;

	public SemanticResult search(Long projectId, String text, int limit) {
		if (!projectRepository.existsById(projectId)) {
			throw new NotFoundException("Project " + projectId + " not found");
		}
		if (!store.available()) {
			throw new EmbeddingException("Semantic search unavailable: " + store.unavailableReason());
		}
		if (text == null || text.isBlank()) {
			return new SemanticResult(provider.modelId(), List.of());
		}
		String input = Text.truncate(
			EmbeddingService.prefixed(props.queryPrefix()) + text.strip(),
			props.maxInputChars()
		);
		float[] vector = provider.embed(List.of(input)).get(0);
		return new SemanticResult(
			provider.modelId(),
			store.searchChunks(projectId, provider.modelId(), vector, Math.min(Math.max(limit, 1), 50))
		);
	}
}
