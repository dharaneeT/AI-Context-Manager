package com.contextlayer.backend.budget;

import com.contextlayer.backend.budget.BudgetModel.Candidate;
import com.contextlayer.backend.budget.BudgetModel.ContextOption;
import java.util.ArrayList;
import java.util.List;

public final class BudgetAllocator {

	public record Choice(String path, ContextOption option, double value) {}

	/** @param chosen one entry per included file, in candidate order; @param skipped files that got nothing */
	public record Allocation(List<Choice> chosen, int usedTokens, List<String> skipped) {}

	private BudgetAllocator() {}

	public static Allocation allocate(List<Candidate> candidates, int budget) {
		int n = candidates.size();
		ContextOption[] current = new ContextOption[n];
		int used = 0;

		while (true) {
			int bestI = -1;
			ContextOption bestOption = null;
			double bestDensity = -1;

			for (int i = 0; i < n; i++) {
				Candidate c = candidates.get(i);
				double currentValue = value(c, current[i]);
				int currentTokens = current[i] == null ? 0 : current[i].tokens();
				for (ContextOption o : c.options()) {
					double gain = value(c, o) - currentValue;
					if (gain <= 1e-12) {
						continue; // not an upgrade
					}
					int delta = o.tokens() - currentTokens;
					if (used + delta > budget) {
						continue; // doesn't fit
					}
					double density = delta <= 0 ? Double.MAX_VALUE : gain / delta; // better AND cheaper: always take
					if (density > bestDensity) {
						bestDensity = density;
						bestI = i;
						bestOption = o;
					}
				}
			}
			if (bestI < 0) {
				break;
			}
			used += bestOption.tokens() - (current[bestI] == null ? 0 : current[bestI].tokens());
			current[bestI] = bestOption;
		}

		List<Choice> chosen = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			Candidate c = candidates.get(i);
			if (current[i] == null) {
				skipped.add(c.path());
			} else {
				chosen.add(new Choice(c.path(), current[i], value(c, current[i])));
			}
		}
		return new Allocation(chosen, used, skipped);
	}

	private static double value(Candidate c, ContextOption option) {
		return option == null ? 0 : c.score() * option.level().fidelity();
	}
}
