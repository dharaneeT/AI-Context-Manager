package com.contextlayer.backend.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextlayer.backend.project.IndexStatus;
import com.contextlayer.backend.project.ProjectRepository;
import com.contextlayer.backend.project.ProjectService;
import com.contextlayer.backend.project.dto.CreateProjectRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class IndexingServiceTest {

	@Autowired
	ProjectService projectService;

	@Autowired
	IndexingService indexingService;

	@Autowired
	ProjectRepository projectRepository;

	@TempDir
	Path dir;

	Long projectId;

	@BeforeEach
	void setUp() throws IOException {
		Files.writeString(dir.resolve("A.java"), "public class A {\n}\n");
		Files.writeString(dir.resolve("B.java"), "public class B {\n}\n");
		projectId = projectService.create(new CreateProjectRequest("demo", dir.toString(), null)).id();
	}

	@AfterEach
	void tearDown() {
		projectRepository.deleteAll();
	}

	@Test
	void indexesIncrementally() throws IOException {
		// 1st run: everything is new
		IndexResult first = indexingService.indexProject(projectId);
		assertThat(first.added()).isEqualTo(2);
		assertThat(first.chunksWritten()).isGreaterThanOrEqualTo(2);

		// 2nd run, nothing changed: nothing re-written
		IndexResult second = indexingService.indexProject(projectId);
		assertThat(second.added()).isZero();
		assertThat(second.updated()).isZero();
		assertThat(second.unchanged()).isEqualTo(2);
		assertThat(second.chunksWritten()).isZero();

		// modify A (size changes), delete B, add C
		Files.writeString(dir.resolve("A.java"), "public class A {\n  int x;\n}\n");
		Files.delete(dir.resolve("B.java"));
		Files.writeString(dir.resolve("C.java"), "public class C {\n}\n");

		IndexResult third = indexingService.indexProject(projectId);
		assertThat(third.updated()).isEqualTo(1);
		assertThat(third.deleted()).isEqualTo(1);
		assertThat(third.added()).isEqualTo(1);

		ProjectStats stats = indexingService.stats(projectId);
		assertThat(stats.status()).isEqualTo(IndexStatus.READY);
		assertThat(stats.files()).isEqualTo(2); // A and C
		assertThat(stats.languages()).extracting(LanguageStat::language).containsExactly("java");
		assertThat(stats.estimatedTokens()).isPositive();
	}
}
