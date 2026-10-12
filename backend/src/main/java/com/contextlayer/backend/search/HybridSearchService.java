package com.contextlayer.backend.search;

import com.contextlayer.backend.config.SearchProperties;
import com.contextlayer.backend.embedding.EmbeddingException;
import com.contextlayer.backend.embedding.SemanticSearchService;
import com.contextlayer.backend.embedding.VectorStore;
import com.contextlayer.backend.search.HybridResults.HybridChunk;
import com.contextlayer.backend.search.HybridResults.HybridFile;
import com.contextlayer.backend.search.HybridResults.HybridResult;
import com.contextlayer.backend.search.ReciprocalRankFusion.Fused;
import com.contextlayer.backend.search.SearchResults.ChunkHit;
import com.contextlayer.backend.search.SearchResults.KeywordSearchResult;
import com.contextlayer.backend.search.SearchResults.NameHit;
import com.contextlayer.backend.search.SearchResults.SummaryHit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HybridSearchService {

	static final String CODE_KEYWORD = "code-keyword";
	static final String CODE_SEMANTIC = "code-semantic";
	static final String SUMMARY = "summary-keyword";
	static final String NAME = "name";

	private final KeywordSearchService keywordSearch;
	private final SemanticSearchService semanticSearch;
	private final VectorStore vectorStore;
	private final SearchProperties props;

	public HybridResult search(Long projectId, String text, int limit) {
		int max = Math.min(Math.max(limit, 1), 50);
		int depth = Math.min(Math.max(props.candidatesPerSignal(), max), 50);

		KeywordSearchResult kw = keywordSearch.search(projectId, text, depth);

		// Semantic search is optional: if it's unavailable we degrade to keyword-only and say so.
		List<ChunkHit> semantic = List.of();
		boolean semanticUsed = false;
		String note = null;
		if (!vectorStore.available()) {
			note = "semantic search unavailable: " + vectorStore.unavailableReason();
		} else {
			try {
				semantic = semanticSearch.search(projectId, text, depth).chunks();
				semanticUsed = true;
				if (semantic.isEmpty()) {
					note = "no embeddings found; run POST /embeddings/refresh";
				}
			} catch (EmbeddingException e) {
				note = e.getMessage();
			}
		}

		Map<String, Double> weights = Map.of(
			CODE_KEYWORD,
			props.keywordWeight(),
			CODE_SEMANTIC,
			props.semanticWeight(),
			SUMMARY,
			props.summaryWeight(),
			NAME,
			props.nameWeight()
		);

		// ---- level 1: chunks ----
		Map<Long, ChunkHit> byId = new HashMap<>();
		kw.chunks().forEach(h -> byId.putIfAbsent(h.chunkId(), h));
		semantic.forEach(h -> byId.putIfAbsent(h.chunkId(), h));

		Map<String, List<String>> chunkLists = new LinkedHashMap<>();
		chunkLists.put(CODE_KEYWORD, kw.chunks().stream().map(h -> String.valueOf(h.chunkId())).toList());
		chunkLists.put(CODE_SEMANTIC, semantic.stream().map(h -> String.valueOf(h.chunkId())).toList());

		List<HybridChunk> chunks = ReciprocalRankFusion
			.fuse(chunkLists, weights, props.rrfK())
			.stream()
			.limit(depth)
			.map(f -> {
				ChunkHit h = byId.get(Long.parseLong(f.key()));
				return new HybridChunk(
					h.chunkId(),
					h.path(),
					h.startLine(),
					h.endLine(),
					round(f.score()),
					h.outline(),
					h.preview(),
					f.ranks()
				);
			})
			.toList();

		// ---- level 2: files. A file's rank in a chunk list is where its FIRST chunk appears. ----
		Map<String, List<String>> fileLists = new LinkedHashMap<>();
		fileLists.put(CODE_KEYWORD, kw.chunks().stream().map(ChunkHit::path).distinct().toList());
		fileLists.put(CODE_SEMANTIC, semantic.stream().map(ChunkHit::path).distinct().toList());
		fileLists.put(SUMMARY, kw.summaries().stream().map(SummaryHit::path).toList());
		fileLists.put(NAME, kw.names().stream().map(NameHit::path).toList());
		Map<String, NameHit> names = kw.names().stream().collect(Collectors.toMap(NameHit::path, n -> n, (a, b) -> a));

		List<HybridFile> files = ReciprocalRankFusion
			.fuse(fileLists, weights, props.rrfK())
			.stream()
			.limit(max)
			.map(f -> new HybridFile(f.key(), round(f.score()), f.ranks(), reasons(f, names.get(f.key()))))
			.toList();

		return new HybridResult(kw.keywords(), semanticUsed, note, chunks, files);
	}

	private static List<String> reasons(Fused f, NameHit name) {
		List<String> out = new ArrayList<>();
		Integer r;
		if ((r = f.ranks().get(CODE_KEYWORD)) != null) {
			out.add("keywords found in its code (#" + r + ")");
		}
		if ((r = f.ranks().get(CODE_SEMANTIC)) != null) {
			out.add("code is semantically close to the task (#" + r + ")");
		}
		if ((r = f.ranks().get(SUMMARY)) != null) {
			out.add("its summary mentions the keywords (#" + r + ")");
		}
		if (name != null) {
			out.addAll(name.reasons());
		}
		return out;
	}

	private static double round(double v) {
		return Math.round(v * 1_000_000d) / 1_000_000d;
	}
}
