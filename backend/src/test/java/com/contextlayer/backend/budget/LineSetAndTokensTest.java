package com.contextlayer.backend.budget;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LineSetAndTokensTest {

	@Test
	void overlappingChunksMergeWithoutDuplicates() {
		LineSet set = new LineSet();
		set.addChunk(1, "l1\nl2\nl3\nl4\nl5");
		set.addChunk(4, "l4\nl5\nl6\nl7\nl8"); // 2-line overlap

		assertThat(set.runs()).hasSize(1);
		assertThat(set.runs().get(0).from()).isEqualTo(1);
		assertThat(set.runs().get(0).to()).isEqualTo(8);
		assertThat(set.render().lines()).hasSize(8);
	}

	@Test
	void gapsBecomeExplicitMarkers() {
		LineSet set = new LineSet();
		set.addChunk(1, "a\nb");
		set.addChunk(6, "c\nd");

		assertThat(set.runs()).hasSize(2);
		assertThat(set.render()).contains("lines 3-5 not shown");
	}

	@Test
	void tokenCounterIsMonotonic() {
		TokenCounter counter = new TokenCounter();

		assertThat(counter.count("")).isZero();
		assertThat(counter.count("public class AuthService {}")).isPositive();
		assertThat(counter.count("hello hello hello hello")).isGreaterThan(counter.count("hello"));
	}
}
