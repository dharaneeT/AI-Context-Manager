package com.contextlayer.backend.summary;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextlayer.backend.indexing.IndexingService;
import com.contextlayer.backend.project.ProjectRepository;
import com.contextlayer.backend.project.ProjectService;
import com.contextlayer.backend.project.dto.CreateProjectRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SummaryServiceTest {

	@Autowired
	ProjectService projectService;

	@Autowired
	IndexingService indexingService;

	@Autowired
	SummaryService summaryService;

	@Autowired
	ProjectRepository projectRepository;

	@TempDir
	Path dir;

	@AfterEach
	void tearDown() {
		projectRepository.deleteAll();
	}

	@Test
	void regeneratesOnlyStaleSummaries() throws Exception {
		Files.writeString(dir.resolve("A.java"), "/** Does A. */\npublic class A {\n  void run() {}\n}\n");
		Files.writeString(dir.resolve("B.java"), "public class B {\n}\n");
		Long id = projectService.create(new CreateProjectRequest("s", dir.toString(), null)).id();
		indexingService.indexProject(id);

		assertThat(summaryService.refresh(id, false).generated()).isEqualTo(2);
		assertThat(summaryService.refresh(id, false).generated()).isZero(); // nothing changed

		Files.writeString(
			dir.resolve("A.java"),
			"/** Does A. */\npublic class A {\n  void run() {}\n  void stop() {}\n}\n"
		);
		indexingService.indexProject(id);

		var result = summaryService.refresh(id, false);
		assertThat(result.generated()).isEqualTo(1); // only A
		assertThat(result.unchanged()).isEqualTo(1);
		assertThat(summaryService.get(id, "A.java").getSummary()).contains("Does A.").contains("stop");
	}
}
