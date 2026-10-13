package com.contextlayer.backend.task;

import com.contextlayer.backend.common.ConflictException;
import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.config.LlmProperties;
import com.contextlayer.backend.llm.AnswerService;
import com.contextlayer.backend.llm.LlmClient;
import com.contextlayer.backend.llm.LlmException;
import com.contextlayer.backend.pack.PackModel.ContextPack;
import com.contextlayer.backend.pack.PackModel.PackItem;
import com.contextlayer.backend.pack.PackService;
import com.contextlayer.backend.project.Project;
import com.contextlayer.backend.project.ProjectRepository;
import com.contextlayer.backend.task.TaskDto.TaskRequest;
import com.contextlayer.backend.task.TaskDto.TaskView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The worker methods are deliberately NOT @Transactional: building a pack and calling a local model can take
 * minutes, so short transactions are opened only around the actual writes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

	private record Job(
		Long id,
		Long projectId,
		String task,
		Integer maxTokens,
		boolean explain,
		boolean recent,
		boolean answer
	) {}

	private final ProjectRepository projectRepository;
	private final TaskRunRepository taskRepository;
	private final TaskRunItemRepository itemRepository;
	private final PackService packService;
	private final AnswerService answerService;
	private final LlmClient llm;
	private final LlmProperties llmProps;
	private final TransactionTemplate tx;
	private final ThreadPoolTaskExecutor executor;

	// ------------------------------------------------------------------ submit

	public TaskView create(Long projectId, TaskRequest request) {
		Project project = projectRepository
			.findById(projectId)
			.orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
		boolean answer = Boolean.TRUE.equals(request.answer());
		if (answer && !llm.configured()) {
			throw new LlmException(
				"An answer was requested, but Ollama is not running. Start it (and run: " +
				"ollama pull " +
				llmProps.model() +
				"), or submit with answer=false.",
				true
			);
		}
		boolean explain = request.explain() == null || request.explain();
		boolean recent = request.recentActivity() == null || request.recentActivity();

		TaskRun run = new TaskRun();
		run.setProject(project);
		run.setTask(request.task().strip());
		run.setMaxTokens(request.maxTokens());
		run.setAnswerRequested(answer);
		run.setExplain(explain);
		run.setRecentActivity(recent);
		run.setStage("Queued");
		run = taskRepository.save(run); // committed before the worker can look for it

		Job job = new Job(run.getId(), projectId, run.getTask(), request.maxTokens(), explain, recent, answer);
		try {
			executor.execute(() -> execute(job));
		} catch (TaskRejectedException e) {
			taskRepository.deleteById(run.getId());
			throw new ConflictException("Too many tasks are queued. Try again in a moment.");
		}
		return TaskView.of(run, null);
	}

	// ------------------------------------------------------------------ worker

	private void execute(Job job) {
		try {
			update(
				job.id(),
				r -> {
					r.setStatus(TaskStatus.RUNNING);
					r.setStage("Building context pack");
					r.setStartedAt(Instant.now());
				}
			);

			ContextPack pack = packService.build(
				job.projectId(),
				job.task(),
				job.maxTokens(),
				job.explain(),
				job.recent()
			);
			savePack(job.id(), pack);

			if (job.answer()) {
				answerStep(job, pack);
			}
			update(
				job.id(),
				r -> {
					r.setStatus(TaskStatus.SUCCEEDED);
					r.setStage("Done");
					r.setFinishedAt(Instant.now());
				}
			);
		} catch (Exception e) {
			log.warn("Task {} failed: {}", job.id(), e.toString());
			String message = Text.truncate(
				e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
				1000
			);
			try {
				update(
					job.id(),
					r -> {
						r.setStatus(TaskStatus.FAILED);
						r.setStage("Failed");
						r.setErrorMessage(message);
						r.setFinishedAt(Instant.now());
					}
				);
			} catch (RuntimeException ignored) {
				log.debug("Could not record the failure of task {}", job.id()); // e.g. the task was deleted
			}
		}
	}

	/** A failed answer doesn't fail the task: the pack is still valuable. */
	private void answerStep(Job job, ContextPack pack) {
		if (pack.stats().packTokens() > llmProps.maxAnswerInputTokens()) {
			setAnswerError(
				job.id(),
				"The pack is " +
				pack.stats().packTokens() +
				" tokens; the limit for the local " +
				"model is " +
				llmProps.maxAnswerInputTokens() +
				" (contextlayer.llm.max-answer-input-tokens). Use a token budget of about 4000 or less."
			);
			return;
		}
		update(job.id(), r -> r.setStage("Asking the local model (can take a few minutes)"));
		try {
			LlmClient.Response response = answerService.answer(job.task(), pack.markdown());
			update(
				job.id(),
				r -> {
					r.setAnswer(response.text());
					r.setAnswerModel(response.model());
					r.setAnswerInputTokens(response.inputTokens());
					r.setAnswerOutputTokens(response.outputTokens());
				}
			);
		} catch (LlmException e) {
			setAnswerError(job.id(), e.getMessage());
		}
	}

	private void setAnswerError(Long id, String message) {
		update(id, r -> r.setAnswerError(Text.truncate(message, 1000)));
	}

	private void savePack(Long id, ContextPack pack) {
		tx.executeWithoutResult(status -> {
			TaskRun r = taskRepository.findById(id).orElseThrow(() -> new NotFoundException("Task deleted"));
			r.setPackMarkdown(pack.markdown());
			r.setPackTokens(pack.stats().packTokens());
			r.setTargetTokens(pack.stats().targetTokens());
			r.setWithinBudget(pack.stats().withinBudget());
			r.setProjectTokens(pack.stats().projectTokens());
			r.setReductionPercent(pack.stats().reductionVsProjectPercent());
			r.setSemanticUsed(pack.semanticUsed());
			r.setNotes(String.join("\n", pack.notes()));

			List<TaskRunItem> items = new ArrayList<>();
			int position = 0;
			for (PackItem i : pack.items()) {
				TaskRunItem item = new TaskRunItem();
				item.setTask(r);
				item.setPosition(position++);
				item.setPath(i.path());
				item.setRole(i.role().name());
				item.setLevel(i.level());
				item.setTokens(i.tokens());
				item.setScore(i.score());
				item.setReasons(String.join("\n", i.reasons()));
				items.add(item);
			}
			itemRepository.saveAll(items);
		});
	}

	private void update(Long id, Consumer<TaskRun> change) {
		tx.executeWithoutResult(status ->
			change.accept(taskRepository.findById(id).orElseThrow(() -> new NotFoundException("Task deleted")))
		);
	}

	// ------------------------------------------------------------------ read

	@Transactional(readOnly = true)
	public TaskView get(Long projectId, Long taskId) {
		TaskRun run = find(projectId, taskId);
		return TaskView.of(run, itemRepository.findByTaskIdOrderByPosition(taskId));
	}

	@Transactional(readOnly = true)
	public List<TaskView> list(Long projectId, int limit) {
		return taskRepository
			.findByProjectIdOrderByCreatedAtDesc(projectId, PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))
			.stream()
			.map(r -> TaskView.of(r, null))
			.toList();
	}

	@Transactional(readOnly = true)
	public String packMarkdown(Long projectId, Long taskId) {
		String markdown = find(projectId, taskId).getPackMarkdown();
		if (markdown == null) {
			throw new NotFoundException("Task " + taskId + " has no pack yet");
		}
		return markdown;
	}

	@Transactional
	public void delete(Long projectId, Long taskId) {
		taskRepository.delete(find(projectId, taskId));
	}

	private TaskRun find(Long projectId, Long taskId) {
		TaskRun run = taskRepository
			.findById(taskId)
			.orElseThrow(() -> new NotFoundException("Task " + taskId + " not found"));
		if (!run.getProject().getId().equals(projectId)) {
			throw new NotFoundException("Task " + taskId + " not found in project " + projectId);
		}
		return run;
	}
}
