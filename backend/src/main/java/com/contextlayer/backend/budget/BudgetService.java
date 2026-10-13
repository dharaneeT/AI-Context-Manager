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

	/** Initial guess for headings and the legend. The pack builder corrects it by measuring the real text. */
	private static final int FRAMING_TOKENS = 150;

	/** The expensive part (ranking, candidate options, token counts), done once per task. */
	public record Prepared(Ranking ranking, String overview, List<Candidate> candidates, long projectTokens) {}

	private final RankingService rankingService;
	private final ProjectOverviewService overviewService;
	private final IndexingService indexingService;
	private final ContextCandidateBuilder builder;
	private final TokenCounter tokens;
	private final BudgetProperties props;

	public Prepared prepare(Long projectId, String task) {
		if (task == null || task.isBlank()) {
			throw new BadRequestException("task must not be blank");
		}
		Ranking ranking = rankingService.rank(projectId, task, props.maxFiles());
		String overview = overviewService.overview(projectId).toText();
		List<Candidate> candidates = builder.build(projectId, ranking, props.maxFiles());
		long projectTokens = indexingService.stats(projectId).estimatedTokens();
		return new Prepared(ranking, overview, candidates, projectTokens);
	}

	public int clampBudget(Integer requested) {
		int wanted = requested == null ? props.defaultTokens() : requested;
		return Math.min(Math.max(wanted, props.minTokens()), props.maxTokens());
	}

	/** The cheap part: pick detail levels for a given budget. extraReserved = tokens the caller needs for framing. */
	public BudgetPlan allocate(Prepared prepared, String task, int max, int extraReserved) {
		int margin = (int) Math.ceil(max * props.safetyMarginPercent() / 100.0);
		int reserved =
			tokens.count(prepared.overview()) + tokens.count(task) + FRAMING_TOKENS + Math.max(0, extraReserved);
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

		Allocation allocation = BudgetAllocator.allocate(prepared.candidates(), available);
		Map<String, Choice> chosen = new HashMap<>();
		allocation.chosen().forEach(c -> chosen.put(c.path(), c));

		List<PlanItem> items = new ArrayList<>();
		int fullFiles = 0;
		for (Candidate c : prepared.candidates()) {
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
		int pack = allocation.usedTokens() + reserved;
		return new BudgetPlan(
			task,
			prepared.ranking().keywords(),
			prepared.ranking().semanticUsed(),
			max,
			margin,
			reserved,
			available,
			allocation.usedTokens(),
			pack,
			prepared.projectTokens(),
			reduction(pack, prepared.projectTokens()),
			fullFiles,
			reduction(allocation.usedTokens(), fullFiles),
			items,
			allocation.skipped()
		);
	}

	/** Day 14 behaviour, unchanged for callers. */
	public BudgetPlan plan(Long projectId, String task, Integer requestedTokens) {
		return allocate(prepare(projectId, task), task, clampBudget(requestedTokens), 0);
	}

	static double reduction(long used, long original) {
		return original <= 0 ? 0 : Math.round(1000.0 * (1.0 - (double) used / original)) / 10.0;
	}
}
