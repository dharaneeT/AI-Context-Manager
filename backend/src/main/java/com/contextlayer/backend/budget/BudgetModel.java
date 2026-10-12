package com.contextlayer.backend.budget;

import com.contextlayer.backend.symbols.CodeSymbol;
import java.util.List;

public final class BudgetModel {

	private BudgetModel() {}

	/** What a contributor proposes: some text at some detail level. Tokens are counted centrally. */
	public record OptionDraft(DetailLevel level, String text) {}

	public record ContextOption(DetailLevel level, String text, int tokens) {}

	public record ChunkText(int startLine, int endLine, String content) {}

	/** Everything a contributor may need about one file. chunks are ordered best-first. */
	public record OptionContext(
		String path,
		String language,
		String content,
		String summary,
		List<CodeSymbol> symbols,
		List<ChunkText> chunks,
		List<String> keywords
	) {}

	public record Candidate(
		String path,
		double score,
		List<String> reasons,
		int fullFileTokens,
		List<ContextOption> options
	) {}
}
