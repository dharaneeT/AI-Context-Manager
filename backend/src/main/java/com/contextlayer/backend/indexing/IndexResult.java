package com.contextlayer.backend.indexing;

import java.util.Map;

public record IndexResult(
	int filesScanned,
	int added,
	int updated,
	int unchanged,
	int deleted,
	int failed,
	int chunksWritten,
	Map<String, Integer> skipped,
	long durationMs
) {}
