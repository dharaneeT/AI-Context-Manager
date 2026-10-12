package com.contextlayer.backend.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReciprocalRankFusion {

	/** @param ranks signal -> 1-based rank; @param contributions signal -> its share of the score */
	public record Fused(String key, double score, Map<String, Integer> ranks, Map<String, Double> contributions) {}

	private ReciprocalRankFusion() {}

	public static List<Fused> fuse(Map<String, List<String>> rankedLists, Map<String, Double> weights, int k) {
		Map<String, Map<String, Integer>> ranks = new LinkedHashMap<>();
		Map<String, Map<String, Double>> parts = new LinkedHashMap<>();

		for (Map.Entry<String, List<String>> list : rankedLists.entrySet()) {
			double weight = weights.getOrDefault(list.getKey(), 1.0);
			Set<String> seen = new HashSet<>();
			int rank = 0;
			for (String key : list.getValue()) {
				if (!seen.add(key)) {
					continue; // a duplicate must not take two ranks
				}
				rank++;
				ranks.computeIfAbsent(key, x -> new LinkedHashMap<>()).put(list.getKey(), rank);
				parts.computeIfAbsent(key, x -> new LinkedHashMap<>()).put(list.getKey(), weight / (k + rank));
			}
		}

		List<Fused> out = new ArrayList<>();
		parts.forEach((key, p) ->
			out.add(new Fused(key, p.values().stream().mapToDouble(Double::doubleValue).sum(), ranks.get(key), p))
		);
		out.sort(Comparator.comparingDouble(Fused::score).reversed().thenComparing(Fused::key)); // stable ties
		return out;
	}
}
