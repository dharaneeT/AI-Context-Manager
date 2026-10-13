package com.contextlayer.backend.summary;

import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.config.LlmProperties;
import com.contextlayer.backend.llm.LlmClient;
import com.contextlayer.backend.security.SecretRedactor;
import com.contextlayer.backend.symbols.CodeSymbol;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LlmSummaryGenerator implements SummaryGenerator {

	/** Source code only. Config and data files are the likeliest to contain secrets, so they are never sent. */
	private static final Set<String> CODE_LANGUAGES = Set.of(
		"java",
		"kotlin",
		"javascript",
		"typescript",
		"python",
		"go",
		"rust",
		"csharp",
		"c",
		"cpp",
		"ruby",
		"php",
		"swift",
		"sql",
		"shell"
	);
	/** Small model, small window: about 1,500 tokens of code is plenty for a summary. */
	private static final int MAX_CONTENT_CHARS = 6_000;

	private static final String SYSTEM =
		"""
            Summarize this source file in 2-3 sentences (max 60 words): what it is responsible for,
            its main classes or methods, and what it works with.
            Plain text only. No markdown, no code, no preamble.""";

	private final LlmClient llm;
	private final LlmProperties props;

	@Override
	public String name() {
		return "llm";
	}

	@Override
	public int quality() {
		return 1;
	}

	@Override
	public boolean available() {
		return llm.configured();
	}

	@Override
	public boolean supports(SummaryInput in) {
		return CODE_LANGUAGES.contains(in.language()) && in.lineCount() >= 10 && in.content() != null;
	}

	@Override
	public String summarize(SummaryInput in) {
		String symbols = in
			.symbols()
			.stream()
			.limit(20)
			.map(CodeSymbol::getQualifiedName)
			.collect(Collectors.joining(", "));
		String content = SecretRedactor.redact(Text.truncate(in.content(), MAX_CONTENT_CHARS)).text();
		String user =
			"File: " +
			in.path() +
			" (" +
			in.language() +
			", " +
			in.lineCount() +
			" lines)\n" +
			(symbols.isEmpty() ? "" : "Symbols: " + symbols + "\n") +
			"\n```\n" +
			content +
			"\n```";
		String text = llm.complete(new LlmClient.Request(props.summaryModel(), SYSTEM, user, 200)).text();
		return Text.truncate(text.strip(), 1500);
	}
}
