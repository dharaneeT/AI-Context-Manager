package com.contextlayer.backend.summary;

public interface SummaryGenerator {
	/** Stored with each summary, so we know which summaries to regenerate if the generator changes. */
	String name();

	String summarize(SummaryInput input);
}
