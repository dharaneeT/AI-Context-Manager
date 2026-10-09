package com.contextlayer.backend.git;

import com.contextlayer.backend.git.GitResponses.CoChange;
import com.contextlayer.backend.git.GitResponses.HotFile;
import com.contextlayer.backend.git.GitResponses.RecentCommit;
import com.contextlayer.backend.git.GitResponses.SyncResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/git")
@RequiredArgsConstructor
public class GitController {

	private final GitIntelligenceService service;

	@PostMapping("/sync")
	public SyncResult sync(@PathVariable Long projectId) {
		return service.sync(projectId);
	}

	@GetMapping("/hot-files")
	public List<HotFile> hotFiles(@PathVariable Long projectId, @RequestParam(defaultValue = "20") int limit) {
		return service.hotFiles(projectId, limit);
	}

	@GetMapping("/recent-commits")
	public List<RecentCommit> recentCommits(
		@PathVariable Long projectId,
		@RequestParam(defaultValue = "20") int limit
	) {
		return service.recentCommits(projectId, limit);
	}

	@GetMapping("/co-changes")
	public List<CoChange> coChanges(
		@PathVariable Long projectId,
		@RequestParam String path,
		@RequestParam(defaultValue = "10") int limit
	) {
		return service.coChanges(projectId, path, limit);
	}

	@GetMapping("/working-changes")
	public List<GitStatusParser.WorkingChange> workingChanges(@PathVariable Long projectId) {
		return service.workingChanges(projectId);
	}
}
