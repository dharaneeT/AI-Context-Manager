package com.contextlayer.backend.pack;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextlayer.backend.indexing.IndexingService;
import com.contextlayer.backend.pack.PackModel.ContextPack;
import com.contextlayer.backend.pack.PackModel.PackItem;
import com.contextlayer.backend.project.ProjectRepository;
import com.contextlayer.backend.project.ProjectService;
import com.contextlayer.backend.project.dto.CreateProjectRequest;
import com.contextlayer.backend.summary.SummaryService;
import com.contextlayer.backend.testsupport.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@PostgresIntegrationTest
class PackIntegrationTest {

	@Autowired
	ProjectService projectService;

	@Autowired
	IndexingService indexingService;

	@Autowired
	SummaryService summaryService;

	@Autowired
	PackService packService;

	@Autowired
	ProjectRepository projectRepository;

	@TempDir
	Path dir;

	Long projectId;

	@BeforeEach
	void setUp() throws Exception {
		Files.writeString(
			dir.resolve("AuthService.java"),
			"""
                /** Handles authentication. */
                public class AuthService {
                    int sessionTimeoutSeconds = 30;
                    void refreshToken() {}
                }
                """
		);
		Files.writeString(dir.resolve("Billing.java"), "public class Billing {\n  void charge() {}\n}\n");
		Files.writeString(
			dir.resolve("application.properties"),
			"spring.datasource.password=SuperSecret123\nserver.port=8080\n"
		);
		projectId = projectService.create(new CreateProjectRequest("p", dir.toString(), null)).id();
		indexingService.indexProject(projectId);
		summaryService.refresh(projectId, false);
	}

	@AfterEach
	void tearDown() {
		projectRepository.deleteAll();
	}

	@Test
	void buildsAnExplainedPackThatFitsTheBudget() {
		ContextPack pack = packService.build(projectId, "authentication session timeout", 3000, true, true);

		assertThat(pack.stats().withinBudget()).isTrue();
		assertThat(pack.stats().packTokens()).isLessThanOrEqualTo(pack.stats().targetTokens());
		assertThat(pack.items().get(0).path()).isEqualTo("AuthService.java");
		assertThat(pack.markdown()).contains("## Relevant code").contains("AuthService.java").contains("Why selected");
	}

	@Test
	void neverLeaksSecretsIntoThePack() {
		ContextPack pack = packService.build(projectId, "datasource password configuration", 3000, true, true);

		assertThat(pack.items()).extracting(PackItem::path).contains("application.properties");
		assertThat(pack.markdown()).doesNotContain("SuperSecret123");
	}
}
