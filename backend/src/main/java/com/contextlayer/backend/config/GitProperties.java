package com.contextlayer.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "contextlayer.git")
public record GitProperties(
	@DefaultValue("2000") int maxCommits,
	@DefaultValue("30") int maxFilesPerCommitForCoChange,
	@DefaultValue("2") int minCoChangeCount,
	@DefaultValue("60") int timeoutSeconds
) {}
