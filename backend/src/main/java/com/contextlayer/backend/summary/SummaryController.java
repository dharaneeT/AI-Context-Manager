package com.contextlayer.backend.summary;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class SummaryController {

	public record SummaryResponse(String path, String summary, String generator, Instant generatedAt) {}

	private final SummaryService summaryService;
	private final ProjectOverviewService overviewService;

	@PostMapping("/summaries/refresh")
	public SummaryRefreshResult refresh(
		@PathVariable Long projectId,
		@RequestParam(defaultValue = "false") boolean force
	) {
		return summaryService.refresh(projectId, force);
	}

	@GetMapping("/summaries")
	public SummaryResponse get(@PathVariable Long projectId, @RequestParam String path) {
		FileSummary s = summaryService.get(projectId, path);
		return new SummaryResponse(path, s.getSummary(), s.getGenerator(), s.getGeneratedAt());
	}

	@GetMapping("/overview")
	public ProjectOverview overview(@PathVariable Long projectId) {
		return overviewService.overview(projectId);
	}
}
