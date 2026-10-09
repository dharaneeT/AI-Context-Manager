package com.contextlayer.backend.search;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class KeywordExtractor {

	/** English filler plus generic task verbs that say nothing about WHERE in the code to look. */
	private static final Set<String> STOP = Set.of(
		"a",
		"an",
		"the",
		"and",
		"or",
		"but",
		"if",
		"then",
		"else",
		"of",
		"to",
		"in",
		"on",
		"at",
		"by",
		"for",
		"with",
		"from",
		"as",
		"is",
		"are",
		"was",
		"were",
		"be",
		"been",
		"it",
		"its",
		"this",
		"that",
		"these",
		"those",
		"i",
		"we",
		"you",
		"they",
		"my",
		"our",
		"your",
		"do",
		"does",
		"did",
		"not",
		"no",
		"can",
		"could",
		"should",
		"would",
		"will",
		"how",
		"what",
		"why",
		"when",
		"where",
		"which",
		"who",
		"please",
		"fix",
		"issue",
		"problem",
		"bug",
		"need",
		"want",
		"make",
		"add",
		"update",
		"change",
		"implement",
		"use",
		"get",
		"set",
		"new"
	);

	/** Splits camelCase, snake_case, kebab-case and dotted names; lower-cases; drops noise; dedupes in order. */
	public List<String> extract(String text, int max) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		String spaced = text.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
		Set<String> keywords = new LinkedHashSet<>();
		for (String raw : spaced.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
			if (raw.length() < 2 || STOP.contains(raw)) {
				continue;
			}
			String stem = lightStem(raw);
			if (!STOP.contains(stem)) {
				keywords.add(stem);
			}
			if (keywords.size() >= max) {
				break;
			}
		}
		return List.copyOf(keywords);
	}

	/**
	 * Very light suffix stripping ("sessions" -> "session", "authenticating" -> "authenticat").
	 * Combined with prefix matching in the query, this approximates stemming without damaging identifiers.
	 */
	static String lightStem(String w) {
		if (w.endsWith("ing") && w.length() > 6) {
			return w.substring(0, w.length() - 3);
		}
		if (w.endsWith("ed") && w.length() > 5) {
			return w.substring(0, w.length() - 2);
		}
		if (w.endsWith("es") && w.length() > 5) {
			return w.substring(0, w.length() - 2);
		}
		if (w.endsWith("s") && !w.endsWith("ss") && w.length() > 4) {
			return w.substring(0, w.length() - 1);
		}
		return w;
	}

	/**
	 * OR-query with prefix matching, e.g. "authenticat:* | timeout:*".
	 * Keywords contain only [a-z0-9], so nothing here can break tsquery syntax (no injection).
	 */
	public static String toTsQuery(List<String> keywords) {
		return keywords.stream().map(k -> k + ":*").collect(Collectors.joining(" | "));
	}
}
