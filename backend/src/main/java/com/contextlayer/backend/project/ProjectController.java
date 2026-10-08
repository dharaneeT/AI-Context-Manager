package com.contextlayer.backend.project;

import com.contextlayer.backend.project.dto.CreateProjectRequest;
import com.contextlayer.backend.project.dto.ProjectResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

	private final ProjectService projectService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ProjectResponse create(@Valid @RequestBody CreateProjectRequest request) {
		return projectService.create(request);
	}

	@GetMapping
	public List<ProjectResponse> list() {
		return projectService.list();
	}

	@GetMapping("/{id}")
	public ProjectResponse get(@PathVariable Long id) {
		return projectService.get(id);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		projectService.delete(id);
	}
}
