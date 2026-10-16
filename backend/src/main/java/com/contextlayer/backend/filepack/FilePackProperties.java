package com.contextlayer.backend.filepack;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits for the Task File Pack feature. Every value has a default, so application.properties does not
 * have to change. To override one, add e.g. "contextlayer.file-pack.max-files=50" to application.properties.
 */
@ConfigurationProperties(prefix = "contextlayer.file-pack")
public record FilePackProperties(
	/** Most files in one recommendation list or one ZIP. */
	@DefaultValue("100") int maxFiles,
	/** How many files are ranked when the request does not say. */
	@DefaultValue("30") int discoverFiles,
	/** At most this many files are labelled "Core implementation". */
	@DefaultValue("8") int coreFiles,
	/** Dependencies are looked up for this many of the top core files. */
	@DefaultValue("5") int expandTopFiles,
	/** Most extra dependency files added from the dependency graph. */
	@DefaultValue("20") int maxExtraDependencies,
	/** A single file bigger than this is refused (1 MB). */
	@DefaultValue("1048576") long maxFileSizeBytes,
	/** All files together (before zipping) may not exceed this (20 MB). */
	@DefaultValue("20971520") long maxTotalBytes
) {}
