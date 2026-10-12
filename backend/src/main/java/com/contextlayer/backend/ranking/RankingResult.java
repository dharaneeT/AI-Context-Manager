package com.contextlayer.backend.ranking;

import com.contextlayer.backend.search.HybridResults.HybridChunk;
import java.util.List;
import java.util.Map;

public final class RankingResult {

	private RankingResult() {}

	/** @param components each signal's weighted contribution; they sum to score */
	public record RankedFile(
		String path,
		double score,
		Map<String, Double> components,
		List<String> reasons,
		List<HybridChunk> chunks
	) {}

	public record Ranking(List<String> keywords, boolean semanticUsed, String semanticNote, List<RankedFile> files) {}
}
