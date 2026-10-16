package com.contextlayer.backend.agent;

import com.contextlayer.backend.agent.AgentDto.CreateRequest;
import com.contextlayer.backend.agent.AgentDto.TaskView;
import com.contextlayer.backend.common.ConflictException;
import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.project.Project;
import com.contextlayer.backend.project.ProjectRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Spring Boot owns the task record; the Python service owns the work. There is no scheduler: whenever a task is
 * read, an active one asks the agent for its latest state and saves it. Not @Transactional on purpose, because
 * every read can make an HTTP call and short saves are better than one long transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskService {

	private final ProjectRepository projectRepository;
	private final AgentTaskRepository repository;
	private final AgentServiceClient agent;

	public TaskView create(Long projectId, CreateRequest request) {
		Project project = projectRepository
			.findById(projectId)
			.orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
		agent.ensureReady(); // clear error before anything is saved

		AgentTask task = new AgentTask();
		task.setProject(project);
		task.setCommand(request.command().strip());
		task.setMaxTokens(request.maxTokens());
		task.setStage("Queued");
		task = repository.save(task);
		try {
			agent.createRun(task.getId(), projectId, task.getCommand(), task.getMaxTokens());
		} catch (RuntimeException e) {
			repository.deleteById(task.getId()); // never leave a task that nobody is working on
			throw e;
		}
		return TaskView.of(task, null, null);
	}

	public List<TaskView> list(Long projectId, int limit) {
		int size = Math.min(Math.max(limit, 1), 100);
		return repository
			.findByProjectIdOrderByCreatedAtDesc(projectId, PageRequest.of(0, size))
			.stream()
			.map(t -> t.getStatus().isActive() ? refresh(t).task() : t)
			.map(t -> TaskView.of(t, null, null))
			.toList();
	}

	public TaskView get(Long projectId, Long taskId) {
		AgentTask task = find(projectId, taskId);
		Refreshed refreshed = task.getStatus().isActive()
			? refresh(task)
			: new Refreshed(task, fetchRunQuietly(taskId));
		Map<String, Object> run = refreshed.run();
		return TaskView.of(refreshed.task(), steps(run), items(run));
	}

	public TaskView cancel(Long projectId, Long taskId) {
		AgentTask task = find(projectId, taskId);
		if (!task.getStatus().isActive()) {
			throw new ConflictException("Task " + taskId + " is already " + task.getStatus());
		}
		Optional<Map<String, Object>> run = agent.cancel(taskId);
		if (run.isPresent()) {
			apply(task, run.get());
		} else {
			task.setStatus(AgentTaskStatus.CANCELLED);
			task.setStage("Cancelled");
			task.setFinishedAt(Instant.now());
		}
		task = repository.save(task);
		return TaskView.of(task, steps(run.orElse(null)), items(run.orElse(null)));
	}

	public String packMarkdown(Long projectId, Long taskId) {
		AgentTask task = find(projectId, taskId);
		if (task.getStatus().isActive()) {
			task = refresh(task).task();
		}
		if (task.getPackMarkdown() == null) {
			throw new ConflictException("The context pack is not ready yet (status: " + task.getStatus() + ")");
		}
		return task.getPackMarkdown();
	}

	public void delete(Long projectId, Long taskId) {
		repository.delete(find(projectId, taskId));
	}

	// ------------------------------------------------------------------ sync with the agent

	private record Refreshed(AgentTask task, Map<String, Object> run) {}

	private Refreshed refresh(AgentTask task) {
		try {
			Optional<Map<String, Object>> run = agent.getRun(task.getId());
			if (run.isEmpty()) {
				task.setStatus(AgentTaskStatus.FAILED);
				task.setStage("Lost");
				task.setErrorMessage("The agent service has no record of this task (was its data folder deleted?)");
				task.setFinishedAt(Instant.now());
				return new Refreshed(repository.save(task), null);
			}
			apply(task, run.get());
			return new Refreshed(repository.save(task), run.get());
		} catch (AgentServiceException e) {
			log.debug("Could not refresh task {}: {}", task.getId(), e.getMessage());
			return new Refreshed(task, null); // agent is down: show the last known state instead of failing the read
		}
	}

	private Map<String, Object> fetchRunQuietly(Long taskId) {
		try {
			return agent.getRun(taskId).orElse(null);
		} catch (AgentServiceException e) {
			return null;
		}
	}

	private void apply(AgentTask task, Map<String, Object> run) {
		String status = text(run.get("status"));
		if (status != null) {
			try {
				task.setStatus(AgentTaskStatus.valueOf(status));
			} catch (IllegalArgumentException ignored) {
				log.warn("Unknown agent status '{}'", status);
			}
		}
		task.setStage(Text.truncate(text(run.get("stage")), 100));
		task.setErrorMessage(Text.truncate(text(run.get("error")), 1000));
		if (!task.getStatus().isActive() && task.getFinishedAt() == null) {
			task.setFinishedAt(Instant.now());
		}
		Map<String, Object> pack = asMap(run.get("pack"));
		if (pack != null) {
			Map<String, Object> stats = asMap(pack.get("stats"));
			Map<String, Object> repo = asMap(pack.get("repo"));
			if (stats != null) {
				task.setPackTokens(asInt(stats.get("packTokens")));
				task.setFullFiles(asInt(stats.get("fullFiles")));
				task.setWindowedFiles(asInt(stats.get("windowedFiles")));
				task.setPathOnlyFiles(asInt(stats.get("pathOnlyFiles")));
			}
			if (repo != null) {
				task.setBaseCommit(Text.truncate(text(repo.get("base_commit")), 64));
			}
		}
		if (task.getStatus() == AgentTaskStatus.CONTEXT_READY && task.getPackMarkdown() == null) {
			task.setPackMarkdown(agent.packMarkdown(task.getId()));
		}
	}

	private AgentTask find(Long projectId, Long taskId) {
		return repository
			.findByIdAndProjectId(taskId, projectId)
			.orElseThrow(() -> new NotFoundException("Agent task " + taskId + " not found in project " + projectId));
	}

	// ------------------------------------------------------------------ tiny JSON helpers

	private static String text(Object o) {
		return o == null ? null : o.toString();
	}

	private static Integer asInt(Object o) {
		return o instanceof Number n ? n.intValue() : null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object o) {
		return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> steps(Map<String, Object> run) {
		return run != null && run.get("steps") instanceof List<?> l ? (List<Map<String, Object>>) l : null;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> items(Map<String, Object> run) {
		Map<String, Object> pack = run == null ? null : asMap(run.get("pack"));
		return pack != null && pack.get("items") instanceof List<?> l ? (List<Map<String, Object>>) l : null;
	}
}
