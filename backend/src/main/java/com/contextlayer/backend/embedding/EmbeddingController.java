package com.contextlayer.backend.embedding;

import com.contextlayer.backend.embedding.EmbeddingService.EmbedResult;
import com.contextlayer.backend.embedding.EmbeddingService.EmbeddingStatus;
import com.contextlayer.backend.embedding.SemanticSearchService.SemanticResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class EmbeddingController {

	private final EmbeddingService embeddingService;
	private final SemanticSearchService semanticSearch;

	@PostMapping("/embeddings/refresh")
	public EmbedResult refresh(@PathVariable Long projectId) {
		return embeddingService.refresh(projectId);
	}

	@GetMapping("/embeddings/status")
	public EmbeddingStatus status(@PathVariable Long projectId) {
		return embeddingService.status(projectId);
	}

	@GetMapping("/semantic-search")
	public SemanticResult search(
		@PathVariable Long projectId,
		@RequestParam String q,
		@RequestParam(defaultValue = "10") int limit
	) {
		return semanticSearch.search(projectId, q, limit);
	}
}
