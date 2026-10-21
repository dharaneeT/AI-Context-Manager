package com.contextlayer.backend.agent;

import com.contextlayer.backend.common.ConflictException;
import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.config.AgentProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Talks to the Python agent service. Every call carries the shared secret in X-Agent-Secret. */
@Slf4j
@Component
public class AgentServiceClient {

	private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
	private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

	private final AgentProperties props;
	private final RestClient client;

	public AgentServiceClient(AgentProperties props) {
		this.props = props;
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
			HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
		);
		factory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));
		RestClient.Builder builder = RestClient.builder().baseUrl(props.url()).requestFactory(factory);
		if (props.secret() != null && !props.secret().isBlank()) {
			builder.defaultHeader("X-Agent-Secret", props.secret());
		}
		this.client = builder.build();
	}

	/** Fails fast with a message that says exactly what to fix. */
	public void ensureReady() {
		if (props.secret() == null || props.secret().isBlank()) {
			throw new AgentServiceException(
				"The agent secret is not set. Add AGENT_SHARED_SECRET=... to backend/.env (same value as agent/.env).",
				true
			);
		}
		health();
	}

	public Map<String, Object> health() {
		return call("checking health", () -> client.get().uri("/health").retrieve().body(MAP));
	}

	public Map<String, Object> deepHealth() {
		return call("checking health", () -> client.get().uri("/health/deep").retrieve().body(MAP));
	}

	public Map<String, Object> createRun(Long taskId, Long projectId, String command, Integer maxTokens) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("task_id", taskId);
		body.put("project_id", projectId);
		body.put("command", command);
		if (maxTokens != null) {
			body.put("max_tokens", maxTokens);
		}
		return call(
			"starting the run",
			() -> client.post().uri("/v1/runs").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(MAP)
		);
	}

	/** Empty when the agent has no record of this run (for example its data folder was wiped). */
	public Optional<Map<String, Object>> getRun(Long taskId) {
		return runGet(taskId, "");
	}

	public Optional<Map<String, Object>> cancel(Long taskId) {
		return runPost(taskId, "cancel", null);
	}

	// ------------------------------------------------------------------ generic run actions
	// The agent adds new actions on most days (answers, plan, approve, reject ...). These four methods reach all of
	// them, so this class does not change every day.

	/** GET /v1/runs/{id}{suffix}, for example "" or "/plan". */
	public Optional<Map<String, Object>> runGet(Long taskId, String suffix) {
		try {
			return Optional.ofNullable(
				call("reading the run", () -> client.get().uri("/v1/runs/{id}" + suffix, taskId).retrieve().body(MAP))
			);
		} catch (NotFoundInAgent e) {
			return Optional.empty();
		}
	}

	/** POST /v1/runs/{id}/{action} with an optional JSON body. */
	public Optional<Map<String, Object>> runPost(Long taskId, String action, Object body) {
		return send("POST", taskId, action, body);
	}

	/** PUT /v1/runs/{id}/{action} with a JSON body. */
	public Optional<Map<String, Object>> runPut(Long taskId, String action, Object body) {
		return send("PUT", taskId, action, body);
	}

	/** Plain text (markdown) such as pack.md or plan.md. Null when it does not exist yet. */
	public String runText(Long taskId, String name) {
		try {
			return call(
				"reading " + name,
				() -> client.get().uri("/v1/runs/{id}/{name}", taskId, name).retrieve().body(String.class)
			);
		} catch (NotFoundInAgent e) {
			return null;
		}
	}

	public String packMarkdown(Long taskId) {
		return runText(taskId, "pack.md");
	}

	// ------------------------------------------------------------------ plumbing

	private Optional<Map<String, Object>> send(String method, Long taskId, String action, Object body) {
		try {
			return Optional.ofNullable(
				call(
					"calling " + action,
					() -> {
						RestClient.RequestBodySpec spec =
							(
								"PUT".equals(method)
									? client.put().uri("/v1/runs/{id}/{action}", taskId, action)
									: client.post().uri("/v1/runs/{id}/{action}", taskId, action)
							).contentType(MediaType.APPLICATION_JSON);
						return (body == null ? spec : spec.body(body)).retrieve().body(MAP);
					}
				)
			);
		} catch (NotFoundInAgent e) {
			return Optional.empty();
		}
	}

	private static final class NotFoundInAgent extends RuntimeException {

		NotFoundInAgent() {
			super("not found", null, false, false);
		}
	}

	private static String messageOf(String body) {
		Matcher m = MESSAGE.matcher(body == null ? "" : body);
		return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\n", " ") : Text.truncate(body, 300);
	}

	private <T> T call(String what, Supplier<T> action) {
		try {
			return action.get();
		} catch (ResourceAccessException e) {
			throw new AgentServiceException(
				"The agent service is not running at " +
				props.url() +
				". Start it: cd agent, activate the venv, then: uvicorn app.main:app --port 8001",
				true
			);
		} catch (RestClientResponseException e) {
			int status = e.getStatusCode().value();
			if (status == 404) {
				throw new NotFoundInAgent();
			}
			if (status == 409) { // a rule of the agent said no (wrong state, stale plan version ...): show its reason
				throw new ConflictException(messageOf(e.getResponseBodyAsString()));
			}
			if (status == 401) {
				throw new AgentServiceException(
					"The agent rejected the shared secret. AGENT_SHARED_SECRET in backend/.env must equal the one in agent/.env.",
					false
				);
			}
			log.warn("Agent service error while {}: {} {}", what, status, e.getResponseBodyAsString());
			throw new AgentServiceException(
				"Agent service error while " + what + ": " + messageOf(e.getResponseBodyAsString()),
				false
			);
		}
	}
}
