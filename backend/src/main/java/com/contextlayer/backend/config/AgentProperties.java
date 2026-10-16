package com.contextlayer.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Where the Python agent service runs and the shared secret both sides use. Set under contextlayer.agent.*. */
@ConfigurationProperties(prefix = "contextlayer.agent")
public record AgentProperties(
	@DefaultValue("http://localhost:8001") String url,
	@DefaultValue("") String secret,
	@DefaultValue("20") int timeoutSeconds
) {}
