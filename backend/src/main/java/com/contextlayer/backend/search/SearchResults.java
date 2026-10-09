package com.contextlayer.backend.search;

import java.util.List;

public final class SearchResults {

    private SearchResults() {
    }

    /** A matching slice of code. */
    public record ChunkHit(Long chunkId, String path, int startLine, int endLine, double score,
                           String outline, String preview) {
    }

    /** A file whose natural-language summary matches. */
    public record SummaryHit(String path, double score, String summary) {
    }

    /** A file whose path or symbol names match, with human-readable reasons. */
    public record NameHit(String path, double score, List<String> reasons) {
    }

    public record KeywordSearchResult(List<String> keywords, List<ChunkHit> chunks,
                                      List<SummaryHit> summaries, List<NameHit> names) {
    }
}