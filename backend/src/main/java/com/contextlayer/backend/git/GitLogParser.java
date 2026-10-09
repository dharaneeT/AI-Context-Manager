package com.contextlayer.backend.git;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the output of:
 * git log --numstat --no-renames --pretty=format:%x1e%H%x1f%an%x1f%cI%x1f%s
 * 0x1e (record separator) starts a commit, 0x1f (unit separator) separates header fields.
 * Those control characters can't appear in a commit subject, unlike commas or pipes.
 */
public final class GitLogParser {

	private static final String RS = "\036"; // 0x1e
	private static final String US = "\037"; // 0x1f

	public record FileChange(String path, int added, int deleted) {}

	public record ParsedCommit(
		String hash,
		String author,
		Instant committedAt,
		String subject,
		List<FileChange> files
	) {}

	private GitLogParser() {}

	public static List<ParsedCommit> parse(String output) {
		List<ParsedCommit> commits = new ArrayList<>();
		for (String record : output.split(RS)) {
			if (record.isBlank()) {
				continue;
			}
			String[] lines = record.split("\\R");
			String[] header = lines[0].split(US, -1);
			if (header.length < 4) {
				continue;
			}
			Instant when;
			try {
				when = OffsetDateTime.parse(header[2].strip()).toInstant();
			} catch (RuntimeException e) {
				continue;
			}
			List<FileChange> files = new ArrayList<>();
			for (int i = 1; i < lines.length; i++) {
				String[] parts = lines[i].split("\t", 3); // "added<TAB>deleted<TAB>path"
				if (parts.length == 3) {
					files.add(new FileChange(parts[2], count(parts[0]), count(parts[1])));
				}
			}
			commits.add(new ParsedCommit(header[0].strip(), header[1], when, header[3], files));
		}
		return commits;
	}

	/** Binary files show "-" instead of a number. */
	private static int count(String s) {
		try {
			return Integer.parseInt(s.strip());
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
