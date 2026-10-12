package com.contextlayer.backend.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextlayer.backend.budget.BudgetAllocator.Allocation;
import com.contextlayer.backend.budget.BudgetModel.Candidate;
import com.contextlayer.backend.budget.BudgetModel.ContextOption;
import java.util.List;
import org.junit.jupiter.api.Test;

class BudgetAllocatorTest {

	private static ContextOption opt(DetailLevel level, int tokens) {
		return new ContextOption(level, "x", tokens);
	}

	private static Candidate cand(String path, double score, ContextOption... options) {
		return new Candidate(path, score, List.of(), 0, List.of(options));
	}

	private static DetailLevel level(Allocation a, String path) {
		return a.chosen().stream().filter(c -> c.path().equals(path)).findFirst().orElseThrow().option().level();
	}

	@Test
	void everyoneGetsTheirRichestOptionWhenThereIsRoom() {
		var a = BudgetAllocator.allocate(
			List.of(
				cand(
					"A",
					1.0,
					opt(DetailLevel.PATH_ONLY, 10),
					opt(DetailLevel.SUMMARY, 50),
					opt(DetailLevel.CODE_1, 300)
				)
			),
			10_000
		);

		assertThat(level(a, "A")).isEqualTo(DetailLevel.CODE_1);
		assertThat(a.usedTokens()).isEqualTo(300);
	}

	@Test
	void neverExceedsTheBudgetAndSpendsOnTheBestFile() {
		var options = new ContextOption[] {
			opt(DetailLevel.PATH_ONLY, 10),
			opt(DetailLevel.SUMMARY, 60),
			opt(DetailLevel.CODE_1, 350)
		};
		var a = BudgetAllocator.allocate(List.of(cand("high", 1.0, options), cand("low", 0.2, options)), 400);

		assertThat(a.usedTokens()).isLessThanOrEqualTo(400);
		assertThat(level(a, "high").fidelity()).isGreaterThan(level(a, "low").fidelity());
	}

	@Test
	void aTinyBudgetSelectsNothing() {
		var a = BudgetAllocator.allocate(List.of(cand("A", 1.0, opt(DetailLevel.PATH_ONLY, 10))), 5);

		assertThat(a.chosen()).isEmpty();
		assertThat(a.skipped()).containsExactly("A");
	}

	@Test
	void prefersACheaperOptionThatIsAlsoBetter() {
		var a = BudgetAllocator.allocate(
			List.of(
				cand(
					"A",
					1.0,
					opt(DetailLevel.CODE_1, 400),
					opt(DetailLevel.FOCUSED, 200),
					opt(DetailLevel.CODE_2, 600)
				)
			),
			500
		);

		assertThat(level(a, "A")).isEqualTo(DetailLevel.FOCUSED); // CODE_2 doesn't fit; CODE_1 is worse and costlier
	}
}
