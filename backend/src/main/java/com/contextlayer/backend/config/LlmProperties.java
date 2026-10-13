package com.contextlayer.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "contextlayer.llm")
public record LlmProperties(
	@DefaultValue("http://localhost:11434") String baseUrl,
	@DefaultValue("qwen2.5-coder:3b") String model,
	@DefaultValue("qwen2.5-coder:3b") String summaryModel,
	@DefaultValue("1024") int maxOutputTokens,
	@DefaultValue("5000") int maxAnswerInputTokens,
	@DefaultValue("8192") int contextTokens,
	@DefaultValue("600") int timeoutSeconds,
	@DefaultValue("50") int maxSummaryFilesPerRun,
	@DefaultValue("2m") String keepAlive
) {}
