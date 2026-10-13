package com.contextlayer.backend.summary;

public interface SummaryGenerator {
	String name();

	/** Higher is better. A better summary is never overwritten by a worse one (an LLM summary survives a heuristic run). */
	default int quality() {
		return 0;
	}

	/** False when the generator can't run at all (e.g. Ollama is not running). */
	default boolean available() {
		return true;
	}

	/** False for files this generator should leave to a cheaper one. */
	default boolean supports(SummaryInput input) {
		return true;
	}

	String summarize(SummaryInput input);
}
