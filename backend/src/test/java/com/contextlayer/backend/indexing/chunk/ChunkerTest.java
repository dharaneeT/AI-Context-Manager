package com.contextlayer.backend.indexing.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextlayer.backend.config.IndexingProperties;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ChunkerTest {

	private Chunker chunker(int maxLines, int overlap, int maxChars) {
		var props = new IndexingProperties(1_048_576, Set.of(), Set.of(), Set.of(), maxLines, overlap, maxChars);
		return new Chunker(props, new TokenEstimator());
	}

	@Test
	void emptyContentGivesNoChunks() {
		assertThat(chunker(50, 5, 10_000).chunk("")).isEmpty();
	}

	@Test
	void smallFileIsOneChunk() {
		List<Chunk> chunks = chunker(50, 5, 10_000).chunk("a\nb\nc\n");
		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).startLine()).isEqualTo(1);
		assertThat(chunks.get(0).endLine()).isEqualTo(3);
		assertThat(chunks.get(0).contentHash()).hasSize(64);
	}

	@Test
	void largeFileIsCoveredWithoutGaps() {
		String text = IntStream.rangeClosed(1, 200).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
		List<Chunk> chunks = chunker(50, 5, 100_000).chunk(text);

		assertThat(chunks.size()).isGreaterThan(3);
		assertThat(chunks.get(0).startLine()).isEqualTo(1);
		assertThat(chunks.get(chunks.size() - 1).endLine()).isEqualTo(200);
		for (int i = 1; i < chunks.size(); i++) {
			Chunk prev = chunks.get(i - 1);
			Chunk cur = chunks.get(i);
			assertThat(cur.startLine()).isLessThanOrEqualTo(prev.endLine() + 1); // no gap
			assertThat(cur.startLine()).isGreaterThan(prev.startLine()); // progress
			assertThat(cur.index()).isEqualTo(i);
		}
	}

	@Test
	void prefersCuttingAtABlankLine() {
		String text = IntStream
			.rangeClosed(1, 120)
			.mapToObj(i -> i == 40 ? "" : "code " + i)
			.collect(Collectors.joining("\n"));
		List<Chunk> chunks = chunker(50, 0, 100_000).chunk(text);

		assertThat(chunks.get(0).endLine()).isEqualTo(40); // cut right after the blank line, not at 50
	}

	@Test
	void hugeLinesAreIsolated() {
		String text = ("x".repeat(5000) + "\n").repeat(3);
		assertThat(chunker(80, 5, 6000).chunk(text)).hasSize(3);
	}

	@Test
	void prefersSymbolStartLines() {
		String text = IntStream.rangeClosed(1, 120).mapToObj(i -> "code " + i).collect(Collectors.joining("\n"));
		List<Chunk> chunks = chunker(50, 0, 100_000).chunk(text, java.util.Set.of(45));
		assertThat(chunks.get(0).endLine()).isEqualTo(44); // next chunk begins exactly at line 45
	}
}
