package com.contextlayer.backend.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {

	@Test
	void agreementBeatsASingleFirstPlace() {
		var fused = ReciprocalRankFusion.fuse(Map.of("a", List.of("p", "q"), "b", List.of("r", "q")), Map.of(), 60);

		assertThat(fused.get(0).key()).isEqualTo("q"); // rank 2 in both lists beats rank 1 in one
		assertThat(fused.get(0).ranks()).containsEntry("a", 2).containsEntry("b", 2);
	}

	@Test
	void weightsChangeTheOutcome() {
		var fused = ReciprocalRankFusion.fuse(
			Map.of("a", List.of("p"), "b", List.of("r")),
			Map.of("a", 2.0, "b", 1.0),
			60
		);

		assertThat(fused.get(0).key()).isEqualTo("p");
		assertThat(fused.get(0).score()).isEqualTo(2.0 / 61);
	}

	@Test
	void duplicatesInOneListCountOnce() {
		var fused = ReciprocalRankFusion.fuse(Map.of("a", List.of("p", "p", "q")), Map.of(), 60);

		assertThat(fused).extracting(ReciprocalRankFusion.Fused::key).containsExactly("p", "q");
		assertThat(fused.get(1).ranks()).containsEntry("a", 2); // q is rank 2, not 3
	}

	@Test
	void tiesAreBrokenDeterministically() {
		var fused = ReciprocalRankFusion.fuse(Map.of("a", List.of("x", "y"), "b", List.of("y", "x")), Map.of(), 60);

		assertThat(fused).extracting(ReciprocalRankFusion.Fused::key).containsExactly("x", "y");
	}
}
