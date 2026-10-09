package com.contextlayer.backend.indexing;

import com.contextlayer.backend.project.IndexStatus;
import java.time.Instant;
import java.util.List;

public record ProjectStats(
	IndexStatus status,
	Instant lastIndexedAt,
	long files,
	long chunks,
	long estimatedTokens,
	List<LanguageStat> languages
) {}
