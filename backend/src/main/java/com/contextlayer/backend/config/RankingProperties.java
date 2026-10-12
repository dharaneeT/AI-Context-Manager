package com.contextlayer.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "contextlayer.ranking")
public record RankingProperties(
	@DefaultValue("0.55") double retrievalWeight,
	@DefaultValue("0.15") double graphWeight,
	@DefaultValue("0.10") double coChangeWeight,
	@DefaultValue("0.08") double recencyWeight,
	@DefaultValue("0.07") double workingWeight,
	@DefaultValue("0.05") double churnWeight,
	@DefaultValue("30") double recencyHalfLifeDays,
	@DefaultValue("5") int seedCount,
	@DefaultValue("2") int graphDepth,
	@DefaultValue("0.5") double graphDecay,
	@DefaultValue("40") int candidateFiles
) {}
