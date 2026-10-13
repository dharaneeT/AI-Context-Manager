package com.contextlayer.backend.llm;

import java.util.OptionalInt;

public interface LlmClient {
	record Request(String model, String system, String user, int maxTokens) {}

	record Response(String text, String model, int inputTokens, int outputTokens, String stopReason) {}

	/** True when the LLM backend is reachable and can be used. */
	boolean configured();

	Response complete(Request request);

	/** Prompt-token count according to the model's own tokenizer, or empty if unavailable. */
	OptionalInt countTokens(String model, String system, String user);
}
