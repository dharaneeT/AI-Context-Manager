package com.contextlayer.backend.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.contextlayer.backend.config.RankingProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class FileScorerTest {

	private final RankingProperties props = new RankingProperties(
		0.55,
		0.15,
		0.10,
		0.08,
		0.07,
		0.05,
		30,
		5,
		2,
		0.5,
		40
	);

	@Test
	void recencyHalvesEveryHalfLife() {
		Instant now = Instant.parse("2026-10-10T00:00:00Z");

		assertThat(FileScorer.recency(now, now, 30)).isEqualTo(1.0);
		assertThat(FileScorer.recency(now.minus(30, ChronoUnit.DAYS), now, 30)).isCloseTo(0.5, within(1e-9));
		assertThat(FileScorer.recency(now.minus(60, ChronoUnit.DAYS), now, 30)).isCloseTo(0.25, within(1e-9));
		assertThat(FileScorer.recency(null, now, 30)).isZero();
		assertThat(FileScorer.recency(now.plus(1, ChronoUnit.DAYS), now, 30)).isEqualTo(1.0);
	}

	@Test
	void graphScoreDecaysPerHop() {
		assertThat(FileScorer.graph(1, 0.8, 0.5)).isEqualTo(0.8);
		assertThat(FileScorer.graph(2, 0.8, 0.5)).isEqualTo(0.4);
		assertThat(FileScorer.graph(0, 0.8, 0.5)).isZero();
	}

	@Test
	void churnIsLogScaledAndBounded() {
		assertThat(FileScorer.churn(0, 100)).isZero();
		assertThat(FileScorer.churn(100, 100)).isEqualTo(1.0);
		assertThat(FileScorer.churn(10, 100)).isBetween(0.4, 0.6);
		assertThat(FileScorer.churn(5, 0)).isZero();
	}

	@Test
	void aPerfectMatchBeatsAFileThatOnlyHasBoosters() {
		var match = new FileSignals("match", 1, 0, 0, 0, 0, 0);
		var boosters = new FileSignals("boosters", 0, 1, 1, 1, 1, 1);

		assertThat(FileScorer.score(match, props)).isGreaterThan(FileScorer.score(boosters, props));
	}

	@Test
	void contributionsSumToTheScore() {
		var s = new FileSignals("x", 0.8, 0.3, 0.2, 0.5, 1, 0.4);

		assertThat(FileScorer.contributions(s, props).values().stream().mapToDouble(Double::doubleValue).sum())
			.isCloseTo(FileScorer.score(s, props), within(1e-12));
	}
}
