package com.contextlayer.backend.indexing.chunk;

import com.contextlayer.backend.common.HashUtils;
import com.contextlayer.backend.config.IndexingProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class Chunker {

	/** A line that likely starts a new declaration, near the left margin. A heuristic until Day 8. */
	private static final Pattern DECLARATION = Pattern.compile(
		"^\\s{0,4}(?:(?:public|private|protected|class|interface|enum|record|def|function|export" +
		"|async|fun|func|impl|struct)\\b|@\\w)"
	);

	private final IndexingProperties props;
	private final TokenEstimator estimator;

	public List<Chunk> chunk(String content) {
		List<String> lines = content.lines().toList();
		if (lines.isEmpty()) {
			return List.of();
		}

		int n = lines.size();
		int maxLines = props.chunkMaxLines();
		int overlap = Math.min(props.chunkOverlapLines(), maxLines / 2);

		List<Chunk> chunks = new ArrayList<>();
		int start = 0;
		while (start < n) {
			int end = hardEnd(lines, start, maxLines, props.chunkMaxChars());

			// Not at the end of the file yet: try to cut at a natural boundary
			// in the last quarter of the window.
			if (end < n) {
				int floor = start + (maxLines * 3) / 4;
				for (int i = end; i > floor; i--) {
					if (isBoundary(lines, i)) {
						end = i;
						break;
					}
				}
			}

			chunks.add(build(chunks.size(), lines, start, end));
			if (end >= n) {
				break;
			}
			start = Math.max(end - overlap, start + 1); // overlap, but always make progress
		}
		return chunks;
	}

	/** Largest end index (exclusive) respecting both the line and character caps. Always takes 1+ line. */
	private int hardEnd(List<String> lines, int start, int maxLines, int maxChars) {
		int end = start;
		int chars = 0;
		while (end < lines.size() && end - start < maxLines) {
			int len = lines.get(end).length() + 1;
			if (end > start && chars + len > maxChars) {
				break;
			}
			chars += len;
			end++;
		}
		return end;
	}

	/** Is it a good place to start a new chunk at line index i (i.e. cut between i-1 and i)? */
	private boolean isBoundary(List<String> lines, int i) {
		return lines.get(i - 1).isBlank() || DECLARATION.matcher(lines.get(i)).find();
	}

	private Chunk build(int index, List<String> lines, int start, int end) {
		String text = String.join("\n", lines.subList(start, end));
		return new Chunk(index, start + 1, end, text, estimator.estimate(text), HashUtils.sha256Hex(text));
	}
}
