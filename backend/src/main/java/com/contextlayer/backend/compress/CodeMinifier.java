package com.contextlayer.backend.compress;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class CodeMinifier {

	/** Narrow on purpose: a Javadoc that merely says "license" must not be deleted. */
	private static final Pattern LEGAL = Pattern.compile(
		"(?i)copyright|licensed under|spdx-license|all rights reserved"
	);

	private CodeMinifier() {}

	public static String minify(String code) {
		List<String> lines = stripLicenseHeader(code.lines().toList());
		StringBuilder sb = new StringBuilder();
		boolean previousBlank = true; // true at the start, so leading blank lines vanish
		for (String line : lines) {
			String t = line.stripTrailing();
			boolean blank = t.isEmpty();
			if (blank && previousBlank) {
				continue;
			}
			sb.append(t).append('\n');
			previousBlank = blank;
		}
		return sb.toString().stripTrailing(); // NOT strip(): the first line's indentation matters
	}

	static List<String> stripLicenseHeader(List<String> lines) {
		int s = 0;
		while (s < lines.size() && lines.get(s).isBlank()) {
			s++;
		}
		if (s >= lines.size()) {
			return lines;
		}
		String first = lines.get(s).strip();
		int end = -1;
		if (first.startsWith("/*")) {
			for (int i = s; i < Math.min(lines.size(), s + 60); i++) {
				if (lines.get(i).contains("*/")) {
					end = i;
					break;
				}
			}
		} else if (first.startsWith("//") || (first.startsWith("#") && !first.startsWith("#!"))) {
			String marker = first.startsWith("//") ? "//" : "#";
			int i = s;
			while (i < lines.size() && lines.get(i).strip().startsWith(marker)) {
				i++;
			}
			end = i - 1;
		}
		if (end < 0) {
			return lines;
		}
		String header = String.join("\n", lines.subList(s, end + 1));
		return LEGAL.matcher(header).find() ? new ArrayList<>(lines.subList(end + 1, lines.size())) : lines;
	}
}
