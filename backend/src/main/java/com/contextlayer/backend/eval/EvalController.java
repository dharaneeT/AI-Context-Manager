package com.contextlayer.backend.eval;

import com.contextlayer.backend.eval.EvalModel.EvalReport;
import com.contextlayer.backend.eval.EvalModel.EvalRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class EvalController {

	private final EvalService evalService;

	@PostMapping("/evaluate")
	public EvalReport evaluate(@PathVariable Long projectId, @Valid @RequestBody EvalRequest request) {
		return evalService.evaluate(projectId, request);
	}
}
