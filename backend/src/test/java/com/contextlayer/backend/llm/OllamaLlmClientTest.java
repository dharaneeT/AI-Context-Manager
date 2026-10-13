package com.contextlayer.backend.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.contextlayer.backend.config.LlmProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OllamaLlmClientTest {

	@Test
	void readsTheReplyAndStripsThinkingBlocks() {
		Map<String, Object> response = Map.of(
			"message",
			Map.of("role", "assistant", "content", "<think>hmm</think>Hello world"),
			"prompt_eval_count",
			12,
			"eval_count",
			3
		);

		assertThat(OllamaLlmClient.parseText(response)).isEqualTo("Hello world");
		assertThat(OllamaLlmClient.parseInt(response, "prompt_eval_count")).isEqualTo(12);
		assertThat(OllamaLlmClient.parseInt(response, "missing")).isZero();
		assertThat(OllamaLlmClient.parseText(Map.of())).isEmpty();
	}

	@Test
	void whenOllamaIsNotRunningItIsNotConfiguredAndRefusesToCall() {
		var props = new LlmProperties("http://localhost:1", "m", "s", 100, 1000, 2048, 5, 10, "1m");
		var client = new OllamaLlmClient(props);

		assertThat(client.configured()).isFalse();
		assertThat(client.countTokens("m", null, "hi")).isEmpty();
		assertThatThrownBy(() -> client.complete(new LlmClient.Request("m", null, "hi", 10)))
			.isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.isNotConfigured()).isTrue());
	}
}
