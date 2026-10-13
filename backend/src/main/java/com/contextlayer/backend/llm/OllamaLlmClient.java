package com.contextlayer.backend.llm;

import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.config.LlmProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Talks to a local Ollama server. Free, offline, and nothing leaves your machine. */
@Slf4j
@Component
public class OllamaLlmClient implements LlmClient {

	private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
	private static final long REACHABLE_CACHE_MS = 10_000;

	private final LlmProperties props;
	private final RestClient client; // long read timeout: CPU generation is slow
	private final RestClient pinger; // short timeouts: "is Ollama running?"

	private volatile long checkedAt;
	private volatile boolean reachable;

	public OllamaLlmClient(LlmProperties props) {
		this.props = props;
		this.client = build(Duration.ofSeconds(3), Duration.ofSeconds(props.timeoutSeconds()));
		this.pinger = build(Duration.ofSeconds(2), Duration.ofSeconds(3));
	}

	private RestClient build(Duration connect, Duration read) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
			HttpClient.newBuilder().connectTimeout(connect).build()
		);
		factory.setReadTimeout(read);
		return RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
	}

	/** True when the Ollama server answers. Cached for 10 s so the UI can ask cheaply. */
	@Override
	public boolean configured() {
		long now = System.currentTimeMillis();
		if (now - checkedAt > REACHABLE_CACHE_MS) {
			try {
				pinger.get().uri("/api/tags").retrieve().toBodilessEntity();
				reachable = true;
			} catch (RuntimeException e) {
				reachable = false;
			}
			checkedAt = now;
		}
		return reachable;
	}

	@Override
	public Response complete(Request request) {
		Map<String, Object> result = post(body(request.model(), request.system(), request.user(), request.maxTokens()));
		String text = parseText(result);
		if (text.isBlank()) {
			throw new LlmException("The model returned no text (reason: " + result.get("done_reason") + ")", false);
		}
		return new Response(
			text,
			request.model(),
			parseInt(result, "prompt_eval_count"),
			parseInt(result, "eval_count"),
			String.valueOf(result.get("done_reason"))
		);
	}

	/**
	 * Ollama has no token-count endpoint, so we request ONE output token and read how many prompt tokens
	 * were processed. This is slow (the whole prompt is evaluated) and uses the local model's tokenizer.
	 */
	@Override
	public OptionalInt countTokens(String model, String system, String user) {
		if (!configured()) {
			return OptionalInt.empty();
		}
		try {
			int n = parseInt(post(body(model, system, user, 1)), "prompt_eval_count");
			return n > 0 ? OptionalInt.of(n) : OptionalInt.empty();
		} catch (LlmException e) {
			log.warn("Token counting failed: {}", e.getMessage());
			return OptionalInt.empty();
		}
	}

	// ---- request/response helpers ----

	private Map<String, Object> body(String model, String system, String user, int maxTokens) {
		List<Map<String, String>> messages = new ArrayList<>();
		if (system != null && !system.isBlank()) {
			messages.add(Map.of("role", "system", "content", system));
		}
		messages.add(Map.of("role", "user", "content", user));

		Map<String, Object> options = new LinkedHashMap<>();
		options.put("num_ctx", props.contextTokens());
		options.put("num_predict", maxTokens);
		options.put("temperature", 0.2);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		body.put("messages", messages);
		body.put("stream", false);
		body.put("keep_alive", props.keepAlive());
		body.put("options", options);
		return body;
	}

	/** Also strips <think>…</think>, which reasoning models (deepseek-r1, qwen3) put in the reply. */
	static String parseText(Map<String, Object> response) {
		if (response.get("message") instanceof Map<?, ?> m && m.get("content") instanceof String s) {
			return s.replaceAll("(?s)<think>.*?</think>", "").strip();
		}
		return "";
	}

	static int parseInt(Map<String, Object> response, String key) {
		return response.get(key) instanceof Number n ? n.intValue() : 0;
	}

	// ---- transport ----

	private Map<String, Object> post(Map<String, Object> body) {
		if (!configured()) {
			throw new LlmException(
				"Ollama is not reachable at " + props.baseUrl() + ". Start it, then run: ollama pull " + props.model(),
				true
			);
		}
		try {
			Map<String, Object> response = client.post().uri("/api/chat").body(body).retrieve().body(MAP);
			if (response == null) {
				throw new LlmException("Empty response from Ollama", false);
			}
			return response;
		} catch (RestClientResponseException e) {
			throw new LlmException(describe(e.getStatusCode().value(), e.getResponseBodyAsString()), false);
		} catch (ResourceAccessException e) { // no retry: a local model is slow, not flaky
			reachable = false;
			throw new LlmException(
				"Lost the connection to Ollama (or it timed out after " +
				props.timeoutSeconds() +
				" s): " +
				e.getMessage(),
				false
			);
		}
	}

	private String describe(int status, String responseBody) {
		return switch (status) {
			case 404 -> "Model not found (404). Run: ollama pull " + props.model();
			case 500 -> "Ollama failed (500), often because the model does not fit in RAM. " +
			"Try a smaller model. Details: " +
			Text.truncate(responseBody, 200);
			default -> "Ollama error " + status + ": " + Text.truncate(responseBody, 300);
		};
	}
}
