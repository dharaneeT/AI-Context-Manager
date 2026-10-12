package com.contextlayer.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "contextlayer.embedding")
public record EmbeddingProperties(
	@DefaultValue("hash") String provider,
	@DefaultValue("384") int dimensions,
	@DefaultValue("32") int batchSize,
	@DefaultValue("4000") int maxInputChars,
	@DefaultValue("") String queryPrefix,
	@DefaultValue("") String documentPrefix,
	@DefaultValue("http://localhost:11434") String ollamaUrl,
	@DefaultValue("nomic-embed-text") String ollamaModel,
	@DefaultValue("60") int timeoutSeconds
) {}
