package com.contextlayer.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "contextlayer.budget")
public record BudgetProperties(
	@DefaultValue("6000") int defaultTokens,
	@DefaultValue("500") int minTokens,
	@DefaultValue("100000") int maxTokens,
	@DefaultValue("10") int safetyMarginPercent,
	@DefaultValue("25") int maxFiles,
	@DefaultValue("3") int maxChunksPerFile
) {}
