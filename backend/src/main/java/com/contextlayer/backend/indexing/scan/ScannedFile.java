package com.contextlayer.backend.indexing.scan;

import java.nio.file.Path;
import java.time.Instant;

public record ScannedFile(
	String relativePath, // always '/'-separated
	Path absolutePath,
	String extension,
	String language,
	long sizeBytes,
	Instant lastModified
) {}
