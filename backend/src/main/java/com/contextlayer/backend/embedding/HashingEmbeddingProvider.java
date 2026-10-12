package com.contextlayer.backend.embedding;

import com.contextlayer.backend.config.EmbeddingProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "contextlayer.embedding.provider", havingValue = "hash", matchIfMissing = true)
public class HashingEmbeddingProvider implements EmbeddingProvider {

	private static final Set<String> STOP = Set.of(
		"the",
		"and",
		"for",
		"with",
		"this",
		"that",
		"public",
		"private",
		"protected",
		"static",
		"final",
		"void",
		"return",
		"new",
		"import",
		"package",
		"class",
		"int",
		"string",
		"null",
		"true",
		"false",
		"if",
		"else"
	);

	private final int dimensions;

	public HashingEmbeddingProvider(EmbeddingProperties props) {
		this.dimensions = props.dimensions();
	}

	@Override
	public String modelId() {
		return "hash-v1-" + dimensions;
	}

	@Override
	public int dimensions() {
		return dimensions;
	}

	@Override
	public List<float[]> embed(List<String> texts) {
		return texts.stream().map(this::embedOne).toList();
	}

	float[] embedOne(String text) {
		Map<String, Integer> tf = new HashMap<>();
		for (String token : tokenize(text)) {
			tf.merge("w:" + token, 2, Integer::sum); // whole words weigh double
			if (token.length() >= 5) {
				for (int i = 0; i + 3 <= token.length(); i++) {
					tf.merge("g:" + token.substring(i, i + 3), 1, Integer::sum); // character trigrams
				}
			}
		}
		float[] v = new float[dimensions];
		tf.forEach((feature, count) -> {
			int h = fnv1a(feature);
			float sign = (h & 0x40000000) == 0 ? 1f : -1f; // signed hashing cancels collisions on average
			v[Math.floorMod(h, dimensions)] += sign * (float) (1 + Math.log(count));
		});
		normalize(v);
		return v;
	}

	private static List<String> tokenize(String text) {
		String spaced = text.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
		return java.util.Arrays
			.stream(spaced.split("[^a-z0-9]+"))
			.filter(t -> t.length() >= 2 && !STOP.contains(t))
			.toList();
	}

	private static int fnv1a(String s) {
		int h = 0x811c9dc5;
		for (int i = 0; i < s.length(); i++) {
			h ^= s.charAt(i);
			h *= 0x01000193;
		}
		return h;
	}

	private static void normalize(float[] v) {
		double sum = 0;
		for (float x : v) {
			sum += x * x;
		}
		if (sum == 0) {
			v[0] = 1f; // a zero vector has no direction, so cosine distance would be NaN
			return;
		}
		float norm = (float) Math.sqrt(sum);
		for (int i = 0; i < v.length; i++) {
			v[i] /= norm;
		}
	}
}
