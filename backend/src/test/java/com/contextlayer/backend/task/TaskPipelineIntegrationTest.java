package com.contextlayer.backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextlayer.backend.indexing.IndexingService;
import com.contextlayer.backend.project.ProjectRepository;
import com.contextlayer.backend.project.ProjectService;
import com.contextlayer.backend.project.dto.CreateProjectRequest;
import com.contextlayer.backend.summary.SummaryService;
import com.contextlayer.backend.task.TaskDto.TaskRequest;
import com.contextlayer.backend.task.TaskDto.TaskView;
import com.contextlayer.backend.testsupport.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@PostgresIntegrationTest
class TaskPipelineIntegrationTest {

	@Autowired
	ProjectService projectService;

	@Autowired
	IndexingService indexingService;

	@Autowired
	SummaryService summaryService;

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
	void producesAPersistedPack() throws Exception {
		Files.writeString(
			dir.resolve("AuthService.java"),
			"/** Handles authentication. */\npublic class AuthService {\n  int sessionTimeoutSeconds = 30;\n}\n"
		);
		Long id = projectService.create(new CreateProjectRequest("t", dir.toString(), null)).id();
		indexingService.indexProject(id);
		summaryService.refresh(id, false);

		TaskView created = taskService.create(
			id,
			new TaskRequest("authentication session timeout", 3000, false, true, true)
		);
		TaskView done = null;
		for (
			int i = 0;
			i < 60 && (done == null || done.status().equals("QUEUED") || done.status().equals("RUNNING"));
			i++
		) {
			Thread.sleep(500);
			done = taskService.get(id, created.id());
		}

		assertThat(done.status()).isEqualTo("SUCCEEDED");
		assertThat(done.pack().withinBudget()).isTrue();
		assertThat(done.pack().items().get(0).path()).isEqualTo("AuthService.java");
		assertThat(done.pack().markdown()).contains("AuthService.java");
		assertThat(done.answer()).isNull();
		assertThat(taskService.list(id, 10)).hasSize(1);
		assertThat(taskService.packMarkdown(id, created.id())).startsWith("# Context pack");
	}
}
