package com.contextlayer.backend.indexing.scan;

import java.nio.file.Path;
import java.time.Instant;

//represents one discovered file, including its paths, extension, language, size and last-modified time.
public record ScannedFile(
	String relativePath, // always '/'-separated
	Path absolutePath,
	String extension,
	String language,
	long sizeBytes,
	Instant lastModified
) {}
