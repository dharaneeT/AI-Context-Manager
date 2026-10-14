package com.contextlayer.backend.eval;

import java.util.List;
import java.util.OptionalInt;

public final class EvalMetrics {

	private EvalMetrics() {}

	/** "IgnoreRules.java" matches "a/b/IgnoreRules.java" and the exact path, but not "NotIgnoreRules.java". */
	public static boolean matches(String path, String expected) {
		return path.equals(expected) || path.endsWith("/" + expected);
	}

	/** 1-based rank of the first result that matches any expected entry. */
	public static OptionalInt firstHitRank(List<String> ordered, List<String> expected) {
		for (int i = 0; i < ordered.size(); i++) {
			String path = ordered.get(i);
			if (expected.stream().anyMatch(e -> matches(path, e))) {
				return OptionalInt.of(i + 1);
			}
		}
		return OptionalInt.empty();
	}

	/** Fraction of expected entries matched by something in the first k results. */
	public static double recallAtK(List<String> ordered, List<String> expected, int k) {
		if (expected.isEmpty()) {
			return 0;
		}
		List<String> top = ordered.subList(0, Math.min(k, ordered.size()));
		long found = expected.stream().filter(e -> top.stream().anyMatch(p -> matches(p, e))).count();
		return (double) found / expected.size();
	}

	public static double reciprocalRank(OptionalInt rank) {
		return rank.isPresent() ? 1.0 / rank.getAsInt() : 0;
	}
}
