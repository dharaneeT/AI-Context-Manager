package com.contextlayer.backend.symbols;

import java.util.List;

/** Finds where a {...} block that starts at a given line ends. Skips strings and comments. */
final class BraceBlock {

	private BraceBlock() {}

	/**
	 * @param startIdx  0-based index of the declaration line
	 * @param lookahead how many lines after startIdx the opening '{' may appear (0 = same line only)
	 * @return 1-based end line, or startIdx + 1 if no block opens within the lookahead
	 */
	static int endLine(List<String> lines, int startIdx, int lookahead) {
		int depth = 0;
		boolean opened = false;
		boolean inBlockComment = false;
		char inString = 0;

		for (int i = startIdx; i < lines.size(); i++) {
			if (!opened && i > startIdx + lookahead) {
				break;
			}
			String line = lines.get(i);
			for (int k = 0; k < line.length(); k++) {
				char c = line.charAt(k);
				char next = k + 1 < line.length() ? line.charAt(k + 1) : 0;
				if (inBlockComment) {
					if (c == '*' && next == '/') {
						inBlockComment = false;
						k++;
					}
					continue;
				}
				if (inString != 0) {
					if (c == '\\') {
						k++;
					} else if (c == inString) {
						inString = 0;
					}
					continue;
				}
				if (c == '/' && next == '/') {
					break; // rest of line is a comment
				}
				if (c == '/' && next == '*') {
					inBlockComment = true;
					k++;
					continue;
				}
				if (c == '"' || c == '\'' || c == '`') {
					inString = c;
					continue;
				}
				if (c == '{') {
					depth++;
					opened = true;
				} else if (c == '}') {
					depth--;
					if (opened && depth <= 0) {
						return i + 1;
					}
				}
			}
			if (inString != 0 && inString != '`') {
				inString = 0; // only template literals span lines
			}
		}
		return startIdx + 1;
	}
}
