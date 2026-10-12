package com.contextlayer.backend.embedding;

import java.util.List;

public interface EmbeddingProvider {
	/** Stored with every vector. Changing it makes old vectors stale, so they are re-embedded. */
	String modelId();

	int dimensions();

	List<float[]> embed(List<String> texts);
}
