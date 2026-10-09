package com.contextlayer.backend.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextlayer.backend.indexing.IndexingService;
import com.contextlayer.backend.project.ProjectRepository;
import com.contextlayer.backend.project.ProjectService;
import com.contextlayer.backend.project.dto.CreateProjectRequest;
import com.contextlayer.backend.search.SearchResults.KeywordSearchResult;
import com.contextlayer.backend.summary.SummaryService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** Runs only if CL_PG_TEST_PASSWORD is set, because full-text search needs a real PostgreSQL. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "CL_PG_TEST_PASSWORD", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5433/contextlayer_test",
        "spring.datasource.username=postgres",
        "spring.datasource.password=${CL_PG_TEST_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
class KeywordSearchServiceTest {

    @Autowired ProjectService projectService;
    @Autowired IndexingService indexingService;
    @Autowired SummaryService summaryService;
    @Autowired KeywordSearchService searchService;
    @Autowired ProjectRepository projectRepository;

    @TempDir
    Path dir;

    @AfterEach
    void tearDown() {
        projectRepository.deleteAll();
    }

    @Test
    void findsRelevantChunksAndSummaries() throws Exception {
        Files.writeString(dir.resolve("AuthService.java"), """
                /** Handles authentication. */
                public class AuthService {
                    int sessionTimeoutSeconds = 30;
                    void refreshToken() {}
                }
                """);
        Files.writeString(dir.resolve("Billing.java"), """
                /** Charges invoices. */
                public class Billing {
                    void charge() {}
                }
                """);
        Long id = projectService.create(new CreateProjectRequest("s", dir.toString(), null)).id();
        indexingService.indexProject(id);
        summaryService.refresh(id, false);

        KeywordSearchResult result = searchService.search(id, "authentication timeout", 10);

        assertThat(result.keywords()).containsExactly("authentication", "timeout");
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks().get(0).path()).isEqualTo("AuthService.java");   // camelCase "sessionTimeoutSeconds" matched "timeout"
        assertThat(result.chunks()).noneMatch(c -> c.path().equals("Billing.java"));
        assertThat(result.summaries().get(0).path()).isEqualTo("AuthService.java");

        KeywordSearchResult byName = searchService.search(id, "refreshToken", 10);
        assertThat(byName.names().get(0).path()).isEqualTo("AuthService.java");
        assertThat(byName.names().get(0).reasons()).anyMatch(r -> r.contains("refreshToken"));
    }
}