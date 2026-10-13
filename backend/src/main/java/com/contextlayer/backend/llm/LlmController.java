package com.contextlayer.backend.llm;

import com.contextlayer.backend.config.LlmProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class LlmController {

	/** configured = "the local LLM server is reachable". */
	public record Status(boolean configured, String model, String summaryModel) {}

	private final LlmClient llm;
	private final LlmProperties props;
	private final TokenCalibrationService calibration;

	@GetMapping("/api/llm/status")
	public Status status() {
		return new Status(llm.configured(), props.model(), props.summaryModel());
	}

	@GetMapping("/api/projects/{projectId}/token-check")
	public TokenCalibrationService.Calibration tokenCheck(
		@PathVariable Long projectId,
		@RequestParam String task,
		@RequestParam(required = false) Integer maxTokens
	) {
		return calibration.calibrate(projectId, task, maxTokens);
	}
}
