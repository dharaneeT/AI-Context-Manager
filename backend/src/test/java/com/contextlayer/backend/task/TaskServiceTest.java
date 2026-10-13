package com.contextlayer.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.llm.LlmException;
import com.contextlayer.backend.project.ProjectRepository;
import com.contextlayer.backend.project.ProjectService;
import com.contextlayer.backend.project.dto.CreateProjectRequest;
import com.contextlayer.backend.task.TaskDto.TaskRequest;
import com.contextlayer.backend.task.TaskDto.TaskView;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "contextlayer.llm.base-url=http://localhost:1")
class TaskServiceTest {

	@Autowired
	ProjectService projectService;

	@Autowired
	TaskService taskService;

	@Autowired
	ProjectRepository projectRepository;

	@TempDir
	Path dir;

	@AfterEach
	void tearDown() {
		projectRepository.deleteAll();
	}

	@Test
	void askingForAnAnswerWithoutAKeyIsRejectedUpFront() {
		Long id = projectService.create(new CreateProjectRequest("t", dir.toString(), null)).id();

		assertThatThrownBy(() -> taskService.create(id, new TaskRequest("fix it", null, true, null, null)))
			.isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.isNotConfigured()).isTrue());
	}

	@Test
	void aFailingTaskEndsAsFailedWithAMessageInsteadOfHanging() throws Exception {
		Long id = projectService.create(new CreateProjectRequest("t", dir.toString(), null)).id();

		TaskView created = taskService.create(id, new TaskRequest("anything", null, false, null, null));
		assertThat(created.status()).isEqualTo("QUEUED");
		assertThat(created.pack()).isNull();

		TaskView done = waitUntilFinished(id, created.id());

		assertThat(done.status()).isEqualTo("FAILED"); // on H2 the full-text query can't run
		assertThat(done.error()).isNotBlank();
		assertThat(done.finishedAt()).isNotNull();
	}

	@Test
	void tasksAreScopedToTheirProject() {
		Long id = projectService.create(new CreateProjectRequest("t", dir.toString(), null)).id();
		TaskView created = taskService.create(id, new TaskRequest("x", null, false, null, null));

		assertThatThrownBy(() -> taskService.get(id + 999, created.id())).isInstanceOf(NotFoundException.class);
	}

	private TaskView waitUntilFinished(Long projectId, Long taskId) throws InterruptedException {
		for (int i = 0; i < 60; i++) { // up to ~30 s
			TaskView view = taskService.get(projectId, taskId);
			if (!view.status().equals("QUEUED") && !view.status().equals("RUNNING")) {
				return view;
			}
			Thread.sleep(500);
		}
		throw new AssertionError("Task did not finish in time");
	}
}
