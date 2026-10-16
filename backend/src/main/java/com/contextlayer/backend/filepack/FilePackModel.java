package com.contextlayer.backend.filepack;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.file.Path;
import java.util.List;

/** Request and response shapes for the Task File Pack endpoints. */
public final class FilePackModel {

	private FilePackModel() {}

	public enum Category {
		CORE("Core implementation files"),
		DEPENDENCY("Dependencies"),
		SUPPORTING("Supporting files (tests and configuration)"),
		CONTEXT_ONLY("Context-only files");

		private final String label;

		Category(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	public record DiscoverRequest(@NotBlank @Size(max = 4000) String task, @Min(1) @Max(200) Integer maxFiles) {}

	public record RecommendedFile(
		String path,
		Category category,
		String categoryLabel,
		double score,
		String reason,
		long sizeBytes,
		int estimatedTokens,
		boolean selectedByDefault
	) {}

	public record Totals(int files, long sizeBytes, long estimatedTokens) {}

	public record DiscoverResponse(
		String task,
		boolean semanticUsed,
		String disclaimer,
		List<RecommendedFile> files,
		Totals totals,
		List<String> notes
	) {}

	public record FilePreview(String path, long sizeBytes, int estimatedTokens, int redactions, String content) {}

	public record ExportRequest(
		@NotBlank @Size(max = 4000) String task,
		@NotNull @Size(max = 500) List<@NotBlank @Size(max = 1000) String> paths
	) {}

	public record ExcludedFile(String path, String reason) {}

	/** The finished ZIP sits in a temporary file; the controller streams it and then deletes it. */
	public record ExportResult(Path file, String fileName, long sizeBytes, int included, int excluded) {}
}
