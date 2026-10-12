package com.contextlayer.backend.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextlayer.backend.config.EmbeddingProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class HashingEmbeddingProviderTest {

	private final HashingEmbeddingProvider provider = new HashingEmbeddingProvider(
		new EmbeddingProperties("hash", 384, 32, 4000, "", "", "", "", 60)
	);

	private static double cosine(float[] a, float[] b) {
		double dot = 0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
		}
		return dot; // vectors are unit length
	}

	@Test
	void producesDeterministicUnitVectors() {
		float[] a = provider.embed(List.of("session timeout")).get(0);
		float[] b = provider.embed(List.of("session timeout")).get(0);

		assertThat(a).hasSize(384).isEqualTo(b);
		assertThat(cosine(a, a)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
	}

	@Test
	void relatedTextIsCloserThanUnrelatedText() {
		float[] query = provider.embed(List.of("session timeout seconds for authentication")).get(0);
		float[] related = provider.embed(List.of("AuthService sessionTimeoutSeconds authenticate user")).get(0);
		float[] unrelated = provider.embed(List.of("invoice billing charge payment receipt")).get(0);

		assertThat(cosine(query, related)).isGreaterThan(cosine(query, unrelated) + 0.2);
	}

	@Test
	void blankTextStillGivesAValidVector() {
		assertThat(provider.embed(List.of("")).get(0)).hasSize(384).contains(1f);
	}

	@Test
	void formatsPgvectorLiterals() {
		assertThat(VectorStore.literal(new float[] { 0.5f, -1f, Float.NaN })).isEqualTo("[0.5,-1.0,0.0]");
	}
}
