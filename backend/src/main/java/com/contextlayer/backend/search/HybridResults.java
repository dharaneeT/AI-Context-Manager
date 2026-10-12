package com.contextlayer.backend.search;

import java.util.List;
import java.util.Map;

public final class HybridResults {

	private HybridResults() {}

	public record HybridChunk(
		Long chunkId,
		String path,
		int startLine,
		int endLine,
		double score,
		String outline,
		String preview,
		Map<String, Integer> ranks
	) {}

	public record HybridFile(String path, double score, Map<String, Integer> ranks, List<String> reasons) {}

	public record HybridResult(
		List<String> keywords,
		boolean semanticUsed,
		String semanticNote,
		List<HybridChunk> chunks,
		List<HybridFile> files
	) {}
}
