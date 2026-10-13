package com.contextlayer.backend.llm;

public class LlmException extends RuntimeException {

	private final boolean notConfigured;

	public LlmException(String message, boolean notConfigured) {
		super(message);
		this.notConfigured = notConfigured;
	}

	/** True when the LLM backend (Ollama) isn't reachable: a 503 for the caller, not a bad gateway. */
	public boolean isNotConfigured() {
		return notConfigured;
	}
}
