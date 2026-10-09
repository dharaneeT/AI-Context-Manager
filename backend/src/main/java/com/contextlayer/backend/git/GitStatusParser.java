package com.contextlayer.backend.git;

import java.util.ArrayList;
import java.util.List;

/** Parses `git status --porcelain=v1 -z`. NUL-separated, so filenames with spaces are safe. */
public final class GitStatusParser {

	public record WorkingChange(String status, String path) {}

	private GitStatusParser() {}

	public static List<WorkingChange> parse(String output) {
		List<WorkingChange> changes = new ArrayList<>();
		String[] entries = output.split("\0");
		for (int i = 0; i < entries.length; i++) {
			String entry = entries[i];
			if (entry.length() < 4) {
				continue;
			}
			char x = entry.charAt(0);
			char y = entry.charAt(1);
			changes.add(new WorkingChange(entry.substring(0, 2).strip(), entry.substring(3)));
			if (x == 'R' || x == 'C' || y == 'R' || y == 'C') {
				i++; // renames/copies are followed by an extra entry holding the ORIGINAL path
			}
		}
		return changes;
	}
}
