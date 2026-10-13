package com.contextlayer.backend.task;

import com.contextlayer.backend.task.TaskDto.TaskRequest;
import com.contextlayer.backend.task.TaskDto.TaskView;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/tasks")
@RequiredArgsConstructor
public class TaskController {

	private final TaskService taskService;

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public TaskView create(@PathVariable Long projectId, @Valid @RequestBody TaskRequest request) {
		return taskService.create(projectId, request);
	}

	@GetMapping
	public List<TaskView> list(@PathVariable Long projectId, @RequestParam(defaultValue = "20") int limit) {
		return taskService.list(projectId, limit);
	}

	@GetMapping("/{taskId}")
	public TaskView get(@PathVariable Long projectId, @PathVariable Long taskId) {
		return taskService.get(projectId, taskId);
	}

	@GetMapping(value = "/{taskId}/pack.md", produces = "text/markdown;charset=UTF-8")
	public String packMarkdown(@PathVariable Long projectId, @PathVariable Long taskId) {
		return taskService.packMarkdown(projectId, taskId);
	}

	@DeleteMapping("/{taskId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long projectId, @PathVariable Long taskId) {
		taskService.delete(projectId, taskId);
	}
}
