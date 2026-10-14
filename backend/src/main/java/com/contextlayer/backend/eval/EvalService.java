package com.contextlayer.backend.eval;

import com.contextlayer.backend.budget.BudgetService;
import com.contextlayer.backend.embedding.EmbeddingException;
import com.contextlayer.backend.embedding.SemanticSearchService;
import com.contextlayer.backend.embedding.VectorStore;
import com.contextlayer.backend.eval.EvalModel.Aggregate;
import com.contextlayer.backend.eval.EvalModel.CaseResult;
import com.contextlayer.backend.eval.EvalModel.EvalCase;
import com.contextlayer.backend.eval.EvalModel.EvalReport;
import com.contextlayer.backend.eval.EvalModel.EvalRequest;
import com.contextlayer.backend.eval.EvalModel.ModeCase;
import com.contextlayer.backend.eval.EvalModel.PackCase;
import com.contextlayer.backend.pack.PackModel.ContextPack;
import com.contextlayer.backend.pack.PackModel.PackItem;
import com.contextlayer.backend.pack.PackModel.Role;
import com.contextlayer.backend.pack.PackService;
import com.contextlayer.backend.ranking.RankingResult.RankedFile;
import com.contextlayer.backend.ranking.RankingService;
import com.contextlayer.backend.search.HybridResults.HybridFile;
import com.contextlayer.backend.search.HybridSearchService;
import com.contextlayer.backend.search.KeywordSearchService;
import com.contextlayer.backend.search.SearchResults.ChunkHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EvalService {

	private static final int DEPTH = 50;

	private final KeywordSearchService keywordSearch;
	private final SemanticSearchService semanticSearch;
	private final VectorStore vectorStore;
	private final HybridSearchService hybridSearch;
	private final RankingService rankingService;
	private final PackService packService;
	private final BudgetService budgetService;

	public EvalReport evaluate(Long projectId, EvalRequest request) {
		int k = Math.min(Math.max(request.k() == null ? 5 : request.k(), 1), 20);
		int maxTokens = budgetService.clampBudget(request.maxTokens());

		List<CaseResult> results = new ArrayList<>();
		for (EvalCase c : request.cases()) {
			Map<String, List<String>> orderings = new LinkedHashMap<>();
			orderings.put("KEYWORD", distinct(keywordSearch.search(projectId, c.task(), DEPTH).chunks()));
			if (vectorStore.available()) {
				orderings.put("SEMANTIC", semantic(projectId, c.task()));
			}
			orderings.put(
				"HYBRID",
				hybridSearch.search(projectId, c.task(), DEPTH).files().stream().map(HybridFile::path).toList()
			);
			orderings.put(
				"RANKED",
				rankingService.rank(projectId, c.task(), DEPTH).files().stream().map(RankedFile::path).toList()
			);

			Map<String, ModeCase> modes = new LinkedHashMap<>();
			orderings.forEach((mode, ordered) -> {
				OptionalInt rank = EvalMetrics.firstHitRank(ordered, c.expected());
				modes.put(
					mode,
					new ModeCase(
						rank.isPresent() ? rank.getAsInt() : null,
						EvalMetrics.recallAtK(ordered, c.expected(), k),
						ordered.subList(0, Math.min(k, ordered.size()))
					)
				);
			});
			results.add(new CaseResult(c.task(), c.expected(), modes, packCase(projectId, c, maxTokens)));
		}
		return new EvalReport(
			k,
			maxTokens,
			results.size(),
			aggregates(results, k),
			mean(results, r -> r.pack().recallInPack()),
			mean(results, r -> r.pack().recallWithCode()),
			mean(results, r -> r.pack().reductionVsProjectPercent()),
			results
		);
	}

	private PackCase packCase(Long projectId, EvalCase c, int maxTokens) {
		ContextPack pack = packService.build(projectId, c.task(), maxTokens, false, false);
		List<String> all = pack.items().stream().map(PackItem::path).toList();
		List<String> code = pack.items().stream().filter(i -> i.role() == Role.PRIMARY).map(PackItem::path).toList();
		List<String> missing = c
			.expected()
			.stream()
			.filter(e -> all.stream().noneMatch(p -> EvalMetrics.matches(p, e)))
			.toList();
		return new PackCase(
			EvalMetrics.recallAtK(all, c.expected(), all.size()),
			EvalMetrics.recallAtK(code, c.expected(), code.size()),
			pack.stats().packTokens(),
			pack.stats().reductionVsProjectPercent(),
			pack.stats().withinBudget(),
			missing
		);
	}

	private List<String> semantic(Long projectId, String task) {
		try {
			return distinct(semanticSearch.search(projectId, task, DEPTH).chunks());
		} catch (EmbeddingException e) {
			return List.of();
		}
	}

	private static List<String> distinct(List<ChunkHit> chunks) {
		return chunks.stream().map(ChunkHit::path).distinct().toList();
	}

	private static List<Aggregate> aggregates(List<CaseResult> results, int k) {
		List<Aggregate> out = new ArrayList<>();
		for (String mode : results.get(0).modes().keySet()) {
			double recall = mean(results, r -> r.modes().get(mode).recallAtK());
			double mrr = mean(
				results,
				r -> {
					Integer rank = r.modes().get(mode).firstHitRank();
					return rank == null ? 0 : 1.0 / rank;
				}
			);
			double hit = mean(
				results,
				r -> {
					Integer rank = r.modes().get(mode).firstHitRank();
					return rank != null && rank <= k ? 1 : 0;
				}
			);
			out.add(new Aggregate(mode, round(recall), round(mrr), round(hit)));
		}
		return out;
	}

	private static double mean(List<CaseResult> results, java.util.function.ToDoubleFunction<CaseResult> f) {
		return round(results.stream().mapToDouble(f).average().orElse(0));
	}

	private static double round(double v) {
		return Math.round(v * 1000d) / 1000d;
	}
}
