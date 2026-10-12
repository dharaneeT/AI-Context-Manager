package com.contextlayer.backend.ranking;

/** Every value is already normalised to 0..1. */
public record FileSignals(
	String path,
	double retrieval,
	double graph,
	double coChange,
	double recency,
	double working,
	double churn
) {}
