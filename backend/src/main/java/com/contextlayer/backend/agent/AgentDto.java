package com.contextlayer.backend.agent;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AgentDto {

	private AgentDto() {}

	public record CreateRequest(
		@NotBlank @Size(min = 3, max = 4000) String command,
		@Min(500) @Max(200000) Integer maxTokens
	) {}

	/** steps and items come straight from the Python run record when it is reachable (null otherwise). */
	public record TaskView(
		Long id,
		String status,
		String stage,
		String command,
		Integer maxTokens,
		String error,
		Instant createdAt,
		Instant finishedAt,
		String baseCommit,
		Integer packTokens,
		Integer fullFiles,
		Integer windowedFiles,
		Integer pathOnlyFiles,
		boolean packReady,
		List<Map<String, Object>> steps,
		List<Map<String, Object>> items
	) {
		static TaskView of(AgentTask t, List<Map<String, Object>> steps, List<Map<String, Object>> items) {
			return new TaskView(
				t.getId(),
				t.getStatus().name(),
				t.getStage(),
				t.getCommand(),
				t.getMaxTokens(),
				t.getErrorMessage(),
				t.getCreatedAt(),
				t.getFinishedAt(),
				t.getBaseCommit(),
				t.getPackTokens(),
				t.getFullFiles(),
				t.getWindowedFiles(),
				t.getPathOnlyFiles(),
				t.getPackMarkdown() != null,
				steps,
				items
			);
		}
	}
}
