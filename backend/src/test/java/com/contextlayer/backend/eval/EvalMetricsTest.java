package com.contextlayer.backend.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EvalMetricsTest {

	private final List<String> ordered = List.of("a/X.java", "a/Chunker.java", "b/IgnoreRules.java", "c/Z.java");

	@Test
	void matchesBySuffixOnAPathBoundary() {
		assertThat(EvalMetrics.matches("a/b/IgnoreRules.java", "IgnoreRules.java")).isTrue();
		assertThat(EvalMetrics.matches("a/b/IgnoreRules.java", "a/b/IgnoreRules.java")).isTrue();
		assertThat(EvalMetrics.matches("a/NotIgnoreRules.java", "IgnoreRules.java")).isFalse();
	}

	@Test
	void firstHitRankIsOneBased() {
		assertThat(EvalMetrics.firstHitRank(ordered, List.of("IgnoreRules.java", "Chunker.java")).getAsInt())
			.isEqualTo(2);
		assertThat(EvalMetrics.firstHitRank(ordered, List.of("Nope.java"))).isEmpty();
	}

	@Test
	void recallCountsTheFractionFoundInTheTopK() {
		List<String> expected = List.of("Chunker.java", "IgnoreRules.java");

		assertThat(EvalMetrics.recallAtK(ordered, expected, 2)).isEqualTo(0.5);
		assertThat(EvalMetrics.recallAtK(ordered, expected, 3)).isEqualTo(1.0);
		assertThat(EvalMetrics.recallAtK(ordered, List.of(), 3)).isZero();
	}

	@Test
	void reciprocalRank() {
		assertThat(EvalMetrics.reciprocalRank(java.util.OptionalInt.of(4))).isEqualTo(0.25);
		assertThat(EvalMetrics.reciprocalRank(java.util.OptionalInt.empty())).isZero();
	}
}
