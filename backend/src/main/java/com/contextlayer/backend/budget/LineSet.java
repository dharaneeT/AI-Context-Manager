package com.contextlayer.backend.budget;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public final class LineSet {

	public record Run(int from, int to, String text) {}

	private final TreeMap<Integer, String> lines = new TreeMap<>();

	public void addChunk(int startLine, String content) {
		int lineNo = startLine;
		for (String line : (Iterable<String>) content.lines()::iterator) {
			lines.put(lineNo++, line); // overlapping chunks write the same text to the same key
		}
	}

	/** Maximal contiguous stretches of lines. */
	public List<Run> runs() {
		List<Run> out = new ArrayList<>();
		Integer from = null;
		int prev = 0;
		StringBuilder sb = new StringBuilder();
		for (var e : lines.entrySet()) {
			if (from != null && e.getKey() != prev + 1) {
				out.add(new Run(from, prev, sb.toString()));
				sb = new StringBuilder();
				from = null;
			}
			if (from == null) {
				from = e.getKey();
			} else {
				sb.append('\n');
			}
			sb.append(e.getValue());
			prev = e.getKey();
		}
		if (from != null) {
			out.add(new Run(from, prev, sb.toString()));
		}
		return out;
	}

	public String render() {
		List<Run> runs = runs();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < runs.size(); i++) {
			if (i > 0) {
				sb
					.append("\n⋯ lines ")
					.append(runs.get(i - 1).to() + 1)
					.append('-')
					.append(runs.get(i).from() - 1)
					.append(" not shown ⋯\n");
			}
			sb.append(runs.get(i).text());
		}
		return sb.toString();
	}
}
