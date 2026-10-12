package com.contextlayer.backend.ranking;

import com.contextlayer.backend.ranking.RankingResult.Ranking;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class RankingController {

	private final RankingService rankingService;

	@GetMapping("/rank")
	public Ranking rank(
		@PathVariable Long projectId,
		@RequestParam String task,
		@RequestParam(defaultValue = "15") int limit
	) {
		return rankingService.rank(projectId, task, Math.min(Math.max(limit, 1), 50));
	}
}
