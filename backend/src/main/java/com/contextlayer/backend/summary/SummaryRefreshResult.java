package com.contextlayer.backend.summary;

public record SummaryRefreshResult(
	int generated,
	int unchanged,
	int skipped,
	int failed,
	String generator,
	String note
) {}
