package com.contextlayer.backend.llm;

import com.contextlayer.backend.budget.TokenCounter;
import com.contextlayer.backend.config.BudgetProperties;
import com.contextlayer.backend.config.LlmProperties;
import com.contextlayer.backend.pack.PackModel.ContextPack;
import com.contextlayer.backend.pack.PackService;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TokenCalibrationService {

	public record Calibration(
		String model,
		int localTokens,
		Integer providerTokens,
		Double ratio,
		int currentMarginPercent,
		Integer suggestedMarginPercent,
		String note
	) {}

	private final PackService packService;
	private final TokenCounter localCounter;
	private final LlmClient llm;
	private final LlmProperties llmProps;
	private final BudgetProperties budgetProps;

	public Calibration calibrate(Long projectId, String task, Integer maxTokens) {
		ContextPack pack = packService.build(projectId, task, maxTokens, true, true);
		int local = localCounter.count(pack.markdown());
		int margin = budgetProps.safetyMarginPercent();

		if (!llm.configured()) {
			return new Calibration(
				llmProps.model(),
				local,
				null,
				null,
				margin,
				null,
				"Ollama is not running, so only the local count is available."
			);
		}
		if (local > llmProps.contextTokens() - 300) {
			return new Calibration(
				llmProps.model(),
				local,
				null,
				null,
				margin,
				null,
				"This pack is larger than the model's context window (" +
				llmProps.contextTokens() +
				" tokens), so the count would be cut off. Use a smaller maxTokens."
			);
		}
		OptionalInt provider = llm.countTokens(llmProps.model(), null, pack.markdown());
		if (provider.isEmpty()) {
			return new Calibration(
				llmProps.model(),
				local,
				null,
				null,
				margin,
				null,
				"Token counting failed. See the server log."
			);
		}
		double ratio = local == 0 ? 1.0 : (double) provider.getAsInt() / local;
		String note =
			"Measured with the tokenizer of " +
			llmProps.model() +
			", which may differ from the model " +
			"you finally paste the pack into. Based on ONE pack: repeat with several tasks and use the " +
			"largest ratio. Ollama may skip tokens it has cached from a previous identical prompt, so " +
			"change the task text between runs.";
		return new Calibration(
			llmProps.model(),
			local,
			provider.getAsInt(),
			Math.round(ratio * 1000) / 1000.0,
			margin,
			suggestMargin(ratio),
			note
		);
	}

	/** The margin must cover the underestimate (ratio - 1), plus a few points of slack. Never below 5%, never above 30%. */
	static int suggestMargin(double ratio) {
		int needed = ratio > 1 ? (int) Math.ceil((ratio - 1) * 100) + 3 : 0;
		return Math.min(30, Math.max(5, needed));
	}
}
