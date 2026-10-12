package com.contextlayer.backend.embedding;

import com.contextlayer.backend.config.EmbeddingProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "contextlayer.embedding.provider", havingValue = "ollama")
public class OllamaEmbeddingProvider implements EmbeddingProvider {

	public record EmbedRequest(String model, List<String> input) {}

	public record EmbedResponse(List<List<Double>> embeddings) {}

	private final RestClient client;
	private final String model;
	private final int dimensions;

	public OllamaEmbeddingProvider(EmbeddingProperties props) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
			HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
		);
		factory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));
		this.client = RestClient.builder().baseUrl(props.ollamaUrl()).requestFactory(factory).build();
		this.model = props.ollamaModel();
		this.dimensions = props.dimensions();
	}

	@Override
	public String modelId() {
		return "ollama:" + model;
	}

	@Override
	public int dimensions() {
		return dimensions;
	}

	@Override
	public List<float[]> embed(List<String> texts) {
		EmbedResponse response;
		try {
			response =
				client
					.post()
					.uri("/api/embed")
					.contentType(MediaType.APPLICATION_JSON)
					.body(new EmbedRequest(model, texts))
					.retrieve()
					.body(EmbedResponse.class);
		} catch (RestClientException e) {
			throw new EmbeddingException(
				"Ollama request failed (is it running and is '" + model + "' pulled?): " + e.getMessage(),
				e
			);
		}
		if (response == null || response.embeddings() == null || response.embeddings().size() != texts.size()) {
			throw new EmbeddingException("Ollama returned an unexpected number of embeddings");
		}
		return response
			.embeddings()
			.stream()
			.map(list -> {
				if (list.size() != dimensions) {
					throw new EmbeddingException(
						"Model '" +
						model +
						"' returned " +
						list.size() +
						" dimensions but contextlayer.embedding.dimensions=" +
						dimensions
					);
				}
				float[] v = new float[list.size()];
				for (int i = 0; i < v.length; i++) {
					v[i] = list.get(i).floatValue();
				}
				return v;
			})
			.toList();
	}
}
