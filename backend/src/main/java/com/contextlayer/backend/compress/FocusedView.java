package com.contextlayer.backend.compress;

import com.contextlayer.backend.symbols.CodeSymbol;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public final class FocusedView {

	private static final int CONTEXT = 2;

	private FocusedView() {}

	/** @return the focused text, or null when no line mentions a keyword (nothing to focus on) */
	public static String build(String content, List<CodeSymbol> symbols, List<String> keywords) {
		List<String> lines = content.lines().toList();
		int n = lines.size();

		// 1. focus lines: 1-based lines that mention any keyword
		TreeSet<Integer> focus = new TreeSet<>();
		for (int i = 1; i <= n; i++) {
			String lower = lines.get(i - 1).toLowerCase(Locale.ROOT);
			for (String kw : keywords) {
				if (lower.contains(kw)) {
					focus.add(i);
					break;
				}
			}
		}
		if (focus.isEmpty()) {
			return null;
		}

		// 2. decide which lines to keep
		boolean[] keep = new boolean[n + 2];
		boolean[] insideLeaf = new boolean[n + 2];
		for (CodeSymbol s : symbols) {
			int start = Math.max(1, s.getStartLine());
			int end = Math.min(n, s.getEndLine());
			if (start > end) {
				continue;
			}
			if (s.getKind().isType()) {
				keep[firstCodeLine(lines, start, end)] = true; // class header
				if (lines.get(end - 1).strip().equals("}")) {
					keep[end] = true; // closing brace of the class
				}
				continue;
			}
			Integer firstFocus = focus.ceiling(start);
			boolean relevant = firstFocus != null && firstFocus <= end;
			if (relevant) {
				mark(keep, start, end); // whole method, in full
			} else {
				keep[firstCodeLine(lines, start, end)] = true; // just the signature line
			}
			mark(insideLeaf, start, end);
		}
		for (int f : focus) {
			if (!insideLeaf[f]) { // field, constant, import... with a match
				mark(keep, Math.max(1, f - CONTEXT), Math.min(n, f + CONTEXT));
			}
		}
		return render(lines, keep);
	}

	private static String render(List<String> lines, boolean[] keep) {
		int n = lines.size();
		StringBuilder sb = new StringBuilder();
		int i = 1;
		while (i <= n) {
			if (keep[i]) {
				sb.append(lines.get(i - 1).stripTrailing()).append('\n');
				i++;
				continue;
			}
			int j = i;
			boolean hasContent = false;
			while (j <= n && !keep[j]) {
				hasContent |= !lines.get(j - 1).isBlank();
				j++;
			}
			if (hasContent) { // runs of blank lines vanish silently
				sb.append("⋯ lines ").append(i).append('-').append(j - 1).append(" omitted\n");
			}
			i = j;
		}
		return sb.toString().stripTrailing();
	}

	/** First line of the declaration, skipping annotations, doc comments and comments. */
	static int firstCodeLine(List<String> lines, int start, int end) {
		for (int i = start; i <= end; i++) {
			String t = lines.get(i - 1).strip();
			if (
				!t.isEmpty() &&
				!t.startsWith("@") &&
				!t.startsWith("//") &&
				!t.startsWith("/*") &&
				!t.startsWith("*") &&
				!t.startsWith("#")
			) {
				return i;
			}
		}
		return start;
	}

	private static void mark(boolean[] flags, int from, int to) {
		for (int i = from; i <= to; i++) {
			flags[i] = true;
		}
	}
}
