package com.contextlayer.backend.pack;

import com.contextlayer.backend.pack.PackModel.ContextPack;
import com.contextlayer.backend.pack.PackModel.PackRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class PackController {

	private final PackService packService;

	/** Structured pack: stats, items with reasons, and the final markdown. */
	@PostMapping("/pack")
	public ContextPack pack(@PathVariable Long projectId, @Valid @RequestBody PackRequest request) {
		return packService.build(projectId, request);
	}

	/** Just the markdown, ready to pipe into an agent: curl -G ... --data-urlencode "task=..." */
	@GetMapping(value = "/pack.md", produces = "text/markdown;charset=UTF-8")
	public String markdown(
		@PathVariable Long projectId,
		@RequestParam String task,
		@RequestParam(required = false) Integer maxTokens
	) {
		return packService.build(projectId, task, maxTokens, true, true).markdown();
	}
}
