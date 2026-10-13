package com.contextlayer.backend.task;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public final class TaskDto {

	private TaskDto() {}

	public record TaskRequest(
		@NotBlank @Size(max = 4000) String task,
		@Min(500) @Max(200000) Integer maxTokens,
		Boolean answer,
		Boolean explain,
		Boolean recentActivity
	) {}

	public record ItemView(String path, String role, String level, int tokens, double score, List<String> reasons) {}

	public record PackView(
		Integer packTokens,
		Integer targetTokens,
		Boolean withinBudget,
		Long projectTokens,
		Double reductionVsProjectPercent,
		Boolean semanticUsed,
		List<String> notes,
		List<ItemView> items,
		String markdown
	) {}

	public record AnswerView(String text, String model, Integer inputTokens, Integer outputTokens) {}

	public record TaskView(
		Long id,
		String status,
		String stage,
		String task,
		Integer maxTokens,
		boolean answerRequested,
		String error,
		Instant createdAt,
		Instant startedAt,
		Instant finishedAt,
		PackView pack,
		AnswerView answer,
		String answerError
	) {
		/** @param items null for list views, which omit the pack body entirely */
		static TaskView of(TaskRun r, List<TaskRunItem> items) {
			PackView pack = null;
			if (items != null && r.getPackMarkdown() != null) {
				pack =
					new PackView(
						r.getPackTokens(),
						r.getTargetTokens(),
						r.getWithinBudget(),
						r.getProjectTokens(),
						r.getReductionPercent(),
						r.getSemanticUsed(),
						r.getNotes() == null || r.getNotes().isBlank()
							? List.of()
							: Arrays.asList(r.getNotes().split("\n")),
						items
							.stream()
							.map(i ->
								new ItemView(
									i.getPath(),
									i.getRole(),
									i.getLevel(),
									i.getTokens(),
									i.getScore(),
									i.getReasons() == null || i.getReasons().isBlank()
										? List.of()
										: Arrays.asList(i.getReasons().split("\n"))
								)
							)
							.toList(),
						r.getPackMarkdown()
					);
			}
			AnswerView answer = r.getAnswer() == null
				? null
				: new AnswerView(
					r.getAnswer(),
					r.getAnswerModel(),
					r.getAnswerInputTokens(),
					r.getAnswerOutputTokens()
				);
			return new TaskView(
				r.getId(),
				r.getStatus().name(),
				r.getStage(),
				r.getTask(),
				r.getMaxTokens(),
				r.isAnswerRequested(),
				r.getErrorMessage(),
				r.getCreatedAt(),
				r.getStartedAt(),
				r.getFinishedAt(),
				pack,
				answer,
				r.getAnswerError()
			);
		}
	}
}
