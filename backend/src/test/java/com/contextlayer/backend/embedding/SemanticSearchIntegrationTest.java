package com.contextlayer.backend.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextlayer.backend.indexing.IndexingService;
import com.contextlayer.backend.project.ProjectRepository;
import com.contextlayer.backend.project.ProjectService;
import com.contextlayer.backend.project.dto.CreateProjectRequest;
import com.contextlayer.backend.testsupport.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@PostgresIntegrationTest
class SemanticSearchIntegrationTest {

	@Autowired
	ProjectService projectService;

	@Autowired
	IndexingService indexingService;

	@Autowired
	EmbeddingService embeddingService;

	@Autowired
	SemanticSearchService semanticSearch;

	@Autowired
	ProjectRepository projectRepository;

	@TempDir
	Path dir;

	@AfterEach
	void tearDown() {
		projectRepository.deleteAll(); // embeddings go with the chunks via ON DELETE CASCADE
	}

	@Test
	void embedsIncrementallyAndFindsRelatedCode() throws Exception {
		Files.writeString(
			dir.resolve("AuthService.java"),
			"public class AuthService {\n  int sessionTimeoutSeconds = 30;\n  void authenticate() {}\n}\n"
		);
		Files.writeString(dir.resolve("Billing.java"), "public class Billing {\n  void chargeInvoice() {}\n}\n");
		Long id = projectService.create(new CreateProjectRequest("e", dir.toString(), null)).id();
		indexingService.indexProject(id);

		assertThat(embeddingService.refresh(id).chunksEmbedded()).isEqualTo(2);
		assertThat(embeddingService.refresh(id).chunksEmbedded()).isZero(); // nothing pending

		var result = semanticSearch.search(id, "session timeout for authentication", 5);
		assertThat(result.chunks().get(0).path()).isEqualTo("AuthService.java");
	}
}
