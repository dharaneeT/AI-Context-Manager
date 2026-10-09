package com.contextlayer.backend.pipeline;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class PipelineController {

	private final ProjectPipelineService pipeline;

	/** The "refresh everything" button. */
	@PostMapping("/refresh")
	public RefreshReport refresh(@PathVariable Long projectId, @RequestParam(defaultValue = "false") boolean force) {
		return pipeline.refreshAll(projectId, force);
	}
}
