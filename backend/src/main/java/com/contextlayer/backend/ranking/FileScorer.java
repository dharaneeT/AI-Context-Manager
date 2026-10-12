package com.contextlayer.backend.ranking;

import com.contextlayer.backend.config.RankingProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FileScorer {

	private FileScorer() {}

	/** Each signal's weighted share of the final score. Shares sum to the score, so it explains itself. */
	public static Map<String, Double> contributions(FileSignals s, RankingProperties p) {
		double total =
			p.retrievalWeight() +
			p.graphWeight() +
			p.coChangeWeight() +
			p.recencyWeight() +
			p.workingWeight() +
			p.churnWeight();
		Map<String, Double> m = new LinkedHashMap<>();
		m.put("retrieval", s.retrieval() * p.retrievalWeight() / total);
		m.put("graph", s.graph() * p.graphWeight() / total);
		m.put("coChange", s.coChange() * p.coChangeWeight() / total);
		m.put("recency", s.recency() * p.recencyWeight() / total);
		m.put("working", s.working() * p.workingWeight() / total);
		m.put("churn", s.churn() * p.churnWeight() / total);
		return m;
	}

	public static double score(FileSignals s, RankingProperties p) {
		return contributions(s, p).values().stream().mapToDouble(Double::doubleValue).sum();
	}

	/** 1.0 now, 0.5 after one half-life, 0.25 after two... A future timestamp counts as "now". */
	public static double recency(Instant lastCommit, Instant now, double halfLifeDays) {
		if (lastCommit == null) {
			return 0;
		}
		double days = Math.max(0, Duration.between(lastCommit, now).toHours() / 24.0);
		return Math.pow(0.5, days / halfLifeDays);
	}

	/** Closeness to a seed: full weight at 1 hop, decaying with each extra hop. */
	public static double graph(int hops, double seedWeight, double decay) {
		return hops <= 0 ? 0 : seedWeight * Math.pow(decay, hops - 1);
	}

	public static double churn(int commits, int maxCommits) {
		return maxCommits <= 0 ? 0 : Math.log1p(commits) / Math.log1p(maxCommits);
	}
}
