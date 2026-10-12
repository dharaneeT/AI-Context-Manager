package com.contextlayer.backend.budget;

import com.contextlayer.backend.budget.BudgetAllocator.Allocation;
import com.contextlayer.backend.budget.BudgetAllocator.Choice;
import com.contextlayer.backend.budget.BudgetModel.Candidate;
import com.contextlayer.backend.budget.BudgetPlan.PlanItem;
import com.contextlayer.backend.common.BadRequestException;
import com.contextlayer.backend.config.BudgetProperties;
import com.contextlayer.backend.indexing.IndexingService;
import com.contextlayer.backend.ranking.RankingResult.Ranking;
import com.contextlayer.backend.ranking.RankingService;
import com.contextlayer.backend.summary.ProjectOverviewService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BudgetService {

	/** Room for the section headings and instructions Day 16 wraps around the items. */
	private static final int FRAMING_TOKENS = 150;

	private final RankingService rankingService;
	private final ProjectOverviewService overviewService;
	private final IndexingService indexingService;
	private final ContextCandidateBuilder builder;
	private final TokenCounter tokens;
	private final BudgetProperties props;

	public BudgetPlan plan(Long projectId, String task, Integer requestedTokens) {
		if (task == null || task.isBlank()) {
			throw new BadRequestException("task must not be blank");
		}
		int max = Math.min(
			Math.max(requestedTokens == null ? props.defaultTokens() : requestedTokens, props.minTokens()),
			props.maxTokens()
		);

		Ranking ranking = rankingService.rank(projectId, task, props.maxFiles());
		String overview = overviewService.overview(projectId).toText();

		int margin = (int) Math.ceil(max * props.safetyMarginPercent() / 100.0);
		int reserved = tokens.count(overview) + tokens.count(task) + FRAMING_TOKENS;
		int available = max - margin - reserved;
		if (available < 100) {
			throw new BadRequestException(
				"maxTokens=" +
				max +
				" leaves only " +
				available +
				" tokens after reserving " +
				(margin + reserved) +
				" for framing and the safety margin"
			);
		}

		List<Candidate> candidates = builder.build(projectId, ranking, props.maxFiles());
		Allocation allocation = BudgetAllocator.allocate(candidates, available);

		Map<String, Choice> chosen = new HashMap<>();
		allocation.chosen().forEach(c -> chosen.put(c.path(), c));

		List<PlanItem> items = new ArrayList<>();
		int fullFiles = 0;
		for (Candidate c : candidates) {
			Choice choice = chosen.get(c.path());
			if (choice != null) {
				items.add(
					new PlanItem(
						c.path(),
						choice.option().level().name(),
						choice.option().tokens(),
						c.fullFileTokens(),
						c.score(),
						c.reasons(),
						choice.option().text()
					)
				);
				fullFiles += c.fullFileTokens();
			}
		}

		long projectTokens = indexingService.stats(projectId).estimatedTokens(); // chars/4 estimate
		int pack = allocation.usedTokens() + reserved;
		return new BudgetPlan(
			task,
			ranking.keywords(),
			ranking.semanticUsed(),
			max,
			margin,
			reserved,
			available,
			allocation.usedTokens(),
			pack,
			projectTokens,
			reduction(pack, projectTokens),
			fullFiles,
			reduction(allocation.usedTokens(), fullFiles),
			items,
			allocation.skipped()
		);
	}

	private static double reduction(long used, long original) {
		return original <= 0 ? 0 : Math.round(1000.0 * (1.0 - (double) used / original)) / 10.0;
	}
}
