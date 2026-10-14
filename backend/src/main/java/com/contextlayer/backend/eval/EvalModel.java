package com.contextlayer.backend.eval;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

public final class EvalModel {

	private EvalModel() {}

	public record EvalCase(@NotBlank String task, @NotEmpty List<String> expected) {}

	public record EvalRequest(@NotEmpty @Valid List<EvalCase> cases, Integer k, Integer maxTokens) {}

	public record ModeCase(Integer firstHitRank, double recallAtK, List<String> top) {}

	public record PackCase(
		double recallInPack,
		double recallWithCode,
		int packTokens,
		double reductionVsProjectPercent,
		boolean withinBudget,
		List<String> missing
	) {}

	public record CaseResult(String task, List<String> expected, Map<String, ModeCase> modes, PackCase pack) {}

	public record Aggregate(String mode, double meanRecallAtK, double mrr, double hitRate) {}

	public record EvalReport(
		int k,
		int maxTokens,
		int cases,
		List<Aggregate> modes,
		double meanPackRecall,
		double meanPackRecallWithCode,
		double meanReductionVsProjectPercent,
		List<CaseResult> results
	) {}
}
