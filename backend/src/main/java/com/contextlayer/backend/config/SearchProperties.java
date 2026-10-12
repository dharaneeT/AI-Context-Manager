package com.contextlayer.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "contextlayer.search")
public record SearchProperties(
	@DefaultValue("60") int rrfK,
	@DefaultValue("40") int candidatesPerSignal,
	@DefaultValue("1.0") double keywordWeight,
	@DefaultValue("1.0") double semanticWeight,
	@DefaultValue("0.6") double summaryWeight,
	@DefaultValue("0.8") double nameWeight
) {}
