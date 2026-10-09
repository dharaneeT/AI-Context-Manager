package com.contextlayer.backend.summary;

import com.contextlayer.backend.indexing.LanguageStat;
import java.util.List;
import java.util.stream.Collectors;

public record ProjectOverview(
	String name,
	long files,
	long estimatedTokens,
	List<LanguageStat> languages,
	List<String> topFolders,
	List<String> entryPoints,
	List<String> mostDependedOn,
	List<String> recentlyActive
) {
	/** Compact orientation text, ready to be the first lines of a context pack (Day 16). */
	public String toText() {
		StringBuilder sb = new StringBuilder();
		sb
			.append("Project \"")
			.append(name)
			.append("\": ")
			.append(files)
			.append(" files, ~")
			.append(estimatedTokens)
			.append(" tokens. ");
		if (!languages.isEmpty()) {
			sb
				.append("Languages: ")
				.append(
					languages
						.stream()
						.map(l -> l.language() + " (" + l.files() + ")")
						.limit(5)
						.collect(Collectors.joining(", "))
				)
				.append(". ");
		}
		if (!topFolders.isEmpty()) {
			sb.append("Layout: ").append(String.join(", ", topFolders)).append(". ");
		}
		if (!entryPoints.isEmpty()) {
			sb.append("Entry points: ").append(String.join(", ", entryPoints)).append(". ");
		}
		if (!mostDependedOn.isEmpty()) {
			sb.append("Most depended-on files: ").append(String.join(", ", mostDependedOn)).append(". ");
		}
		if (!recentlyActive.isEmpty()) {
			sb.append("Recently active: ").append(String.join(", ", recentlyActive)).append('.');
		}
		return sb.toString().strip();
	}
}
