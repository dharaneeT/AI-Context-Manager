package com.contextlayer.backend.budget;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class BudgetController {

	private final BudgetService budgetService;

	@GetMapping("/budget-preview")
	public BudgetPlan preview(
		@PathVariable Long projectId,
		@RequestParam String task,
		@RequestParam(required = false) Integer maxTokens,
		@RequestParam(defaultValue = "false") boolean includeText
	) {
		BudgetPlan plan = budgetService.plan(projectId, task, maxTokens);
		return includeText ? plan : plan.withoutText();
	}
}
