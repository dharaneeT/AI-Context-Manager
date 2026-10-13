package com.contextlayer.backend.pack;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class PackModel {

	private PackModel() {}

	public enum Role {
		PRIMARY,
		RELATED,
		ALSO_SEE;

		public static Role of(String level) {
			return switch (level) {
				case "OUTLINE", "SUMMARY" -> RELATED;
				case "PATH_ONLY" -> ALSO_SEE;
				default -> PRIMARY; // CODE_1..3 and FOCUSED
			};
		}
	}

	public record PackItem(String path, Role role, String level, int tokens, double score, List<String> reasons) {}

	public record PackStats(
		int maxTokens,
		int targetTokens,
		int packTokens,
		boolean withinBudget,
		long projectTokens,
		double reductionVsProjectPercent,
		int fullFileTokens,
		double reductionVsFilesPercent
	) {}

	public record ContextPack(
		String task,
		Instant generatedAt,
		boolean semanticUsed,
		PackStats stats,
		List<PackItem> items,
		List<String> notIncluded,
		List<String> notes,
		String markdown
	) {}

	public record PackRequest(
		@NotBlank @Size(max = 4000) String task,
		@Min(500) @Max(200000) Integer maxTokens,
		Boolean explain,
		Boolean recentActivity
	) {
		public boolean explainOrDefault() {
			return explain == null || explain;
		}

		public boolean recentOrDefault() {
			return recentActivity == null || recentActivity;
		}
	}
}
