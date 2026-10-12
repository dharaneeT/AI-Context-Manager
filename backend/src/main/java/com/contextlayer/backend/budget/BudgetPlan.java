package com.contextlayer.backend.budget;

import java.util.List;

public record BudgetPlan(
	String task,
	List<String> keywords,
	boolean semanticUsed,
	int maxTokens,
	int safetyMarginTokens,
	int reservedTokens,
	int availableTokens,
	int itemTokens,
	int estimatedPackTokens,
	long projectTokens,
	double reductionVsProjectPercent,
	int fullFileTokens,
	double reductionVsFilesPercent,
	List<PlanItem> included,
	List<String> notIncluded
) {
	public record PlanItem(
		String path,
		String level,
		int tokens,
		int fullFileTokens,
		double score,
		List<String> reasons,
		String text
	) {}

	public BudgetPlan withoutText() {
		return new BudgetPlan(
			task,
			keywords,
			semanticUsed,
			maxTokens,
			safetyMarginTokens,
			reservedTokens,
			availableTokens,
			itemTokens,
			estimatedPackTokens,
			projectTokens,
			reductionVsProjectPercent,
			fullFileTokens,
			reductionVsFilesPercent,
			included
				.stream()
				.map(i ->
					new PlanItem(i.path(), i.level(), i.tokens(), i.fullFileTokens(), i.score(), i.reasons(), null)
				)
				.toList(),
			notIncluded
		);
	}
}
