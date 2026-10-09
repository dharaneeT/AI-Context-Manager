package com.contextlayer.backend.indexing.chunk;

/** startLine and endLine are 1-based and inclusive. */
public record Chunk(int index, int startLine, int endLine, String content,
                    int tokenEstimate, String contentHash) {
}