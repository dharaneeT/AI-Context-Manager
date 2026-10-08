package com.contextlayer.backend.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contextlayer.indexing")
public record IndexingProperties(
	long maxFileSizeBytes,
	Set<String> ignoredDirectories,
	Set<String> ignoredExtensions,
	Set<String> ignoredFileNames,
	int chunkMaxLines,
	int chunkOverlapLines,
	int chunkMaxChars
) {}
