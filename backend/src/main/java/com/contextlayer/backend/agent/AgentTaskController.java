package com.contextlayer.backend.agent;

import com.contextlayer.backend.agent.AgentDto.AnswerRequest;
import com.contextlayer.backend.agent.AgentDto.ApproveRequest;
import com.contextlayer.backend.agent.AgentDto.CreateRequest;
import com.contextlayer.backend.agent.AgentDto.PlanEditRequest;
import com.contextlayer.backend.agent.AgentDto.RejectRequest;
import com.contextlayer.backend.agent.AgentDto.TaskView;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AgentTaskController {

	private final AgentTaskService service;
	private final AgentServiceClient agent;

	/** Is the Python agent up, can it reach this backend, and is its model installed? Never throws: it reports. */
	@GetMapping("/agent/status")
	public Map<String, Object> status() {
		Map<String, Object> out = new LinkedHashMap<>();
		try {
			out.put("reachable", true);
			out.put("details", agent.deepHealth());
		} catch (AgentServiceException e) {
			out.put("reachable", false);
			out.put("message", e.getMessage());
		}
		return out;
	}

	@PostMapping("/projects/{projectId}/agent/tasks")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public TaskView create(@PathVariable Long projectId, @Valid @RequestBody CreateRequest request) {
		return service.create(projectId, request);
	}

	@GetMapping("/projects/{projectId}/agent/tasks")
	public List<TaskView> list(@PathVariable Long projectId, @RequestParam(defaultValue = "20") int limit) {
		return service.list(projectId, limit);
	}

	@GetMapping("/projects/{projectId}/agent/tasks/{taskId}")
	public TaskView get(@PathVariable Long projectId, @PathVariable Long taskId) {
		return service.get(projectId, taskId);
	}

	@PostMapping("/projects/{projectId}/agent/tasks/{taskId}/answers")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public TaskView answer(
		@PathVariable Long projectId,
		@PathVariable Long taskId,
		@Valid @RequestBody AnswerRequest request
	) {
		return service.answer(projectId, taskId, request.answers());
	}

	@GetMapping("/projects/{projectId}/agent/tasks/{taskId}/plan")
	public Map<String, Object> plan(@PathVariable Long projectId, @PathVariable Long taskId) {
		return service.plan(projectId, taskId);
	}

	@GetMapping(value = "/projects/{projectId}/agent/tasks/{taskId}/plan.md", produces = "text/markdown;charset=UTF-8")
	public String planMarkdown(@PathVariable Long projectId, @PathVariable Long taskId) {
		return service.planMarkdown(projectId, taskId);
	}

	@PutMapping("/projects/{projectId}/agent/tasks/{taskId}/plan")
	public TaskView editPlan(
		@PathVariable Long projectId,
		@PathVariable Long taskId,
		@Valid @RequestBody PlanEditRequest request
	) {
		return service.editPlan(projectId, taskId, request);
	}

	@PostMapping("/projects/{projectId}/agent/tasks/{taskId}/approve")
	public TaskView approve(
		@PathVariable Long projectId,
		@PathVariable Long taskId,
		@Valid @RequestBody ApproveRequest request
	) {
		return service.approve(projectId, taskId, request);
	}

	@PostMapping("/projects/{projectId}/agent/tasks/{taskId}/reject")
	public TaskView reject(
		@PathVariable Long projectId,
		@PathVariable Long taskId,
		@Valid @RequestBody RejectRequest request
	) {
		return service.reject(projectId, taskId, request);
	}

	@PostMapping("/projects/{projectId}/agent/tasks/{taskId}/cancel")
	public TaskView cancel(@PathVariable Long projectId, @PathVariable Long taskId) {
		return service.cancel(projectId, taskId);
	}

	@GetMapping(value = "/projects/{projectId}/agent/tasks/{taskId}/pack.md", produces = "text/markdown;charset=UTF-8")
	public String packMarkdown(@PathVariable Long projectId, @PathVariable Long taskId) {
		return service.packMarkdown(projectId, taskId);
	}

	@DeleteMapping("/projects/{projectId}/agent/tasks/{taskId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long projectId, @PathVariable Long taskId) {
		service.delete(projectId, taskId);
	}
}