package com.contextlayer.backend.security;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Last line of defence before text leaves the machine (a pack, an LLM prompt).
 * Deliberately errs on the side of OVER-redacting: a masked constant costs nothing, a leaked key does.
 */
public final class SecretRedactor {

	public record Result(String text, int redactions) {}

	private static final String KEYWORDS =
		"password|passwd|pwd|secret|api[_-]?key|access[_-]?key|auth[_-]?token|private[_-]?key|client[_-]?secret";

	/** Whole tokens that are secrets wherever they appear. */
	private static final List<Pattern> TOKENS = List.of(
		Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
		Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), // AWS access key id
		Pattern.compile("\\bsk-[A-Za-z0-9_\\-]{20,}"), // sk-... API keys
		Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b"), // GitHub tokens
		Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")
	); // JWT

	/** name = "value"  where the name mentions a secret word and the value is a quoted literal. */
	private static final Pattern QUOTED = Pattern.compile(
		"(?i)(\\b[\\w.-]*(?:" + KEYWORDS + ")[\\w.-]*\\s*[=:]\\s*)([\"'])([^\"'\\s]{4,})\\2"
	);

	/** properties / .env / YAML style: a whole line "name=value" or "name: value" without quotes. */
	private static final Pattern LINE = Pattern.compile(
		"(?im)^([ \\t]*[\\w.-]*(?:" + KEYWORDS + ")[\\w.-]*[ \\t]*[=:][ \\t]*)([^\\s#\"'][^\\r\\n#]*)$"
	);

	private SecretRedactor() {}

	public static Result redact(String text) {
		if (text == null || text.isEmpty()) {
			return new Result(text, 0);
		}
		int[] count = { 0 };
		String out = text;
		for (Pattern p : TOKENS) {
			out = replace(p, out, "[REDACTED]", count);
		}
		out = replace(QUOTED, out, "$1$2[REDACTED]$2", count);
		out = replace(LINE, out, "$1[REDACTED]", count);
		return new Result(out, count[0]);
	}

	private static String replace(Pattern pattern, String text, String replacement, int[] count) {
		Matcher m = pattern.matcher(text);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			count[0]++;
			m.appendReplacement(sb, replacement);
		}
		m.appendTail(sb);
		return sb.toString();
	}
}
