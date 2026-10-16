package com.contextlayer.backend.filepack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.contextlayer.backend.budget.TokenCounter;
import com.contextlayer.backend.common.BadRequestException;
import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.filepack.FilePackModel.DiscoverRequest;
import com.contextlayer.backend.filepack.FilePackModel.DiscoverResponse;
import com.contextlayer.backend.filepack.FilePackModel.ExportRequest;
import com.contextlayer.backend.filepack.FilePackModel.ExportResult;
import com.contextlayer.backend.filepack.FilePackModel.FilePreview;
import com.contextlayer.backend.graph.DependencyGraph;
import com.contextlayer.backend.graph.DependencyService;
import com.contextlayer.backend.indexing.ProjectFile;
import com.contextlayer.backend.indexing.ProjectFileRepository;
import com.contextlayer.backend.project.Project;
import com.contextlayer.backend.project.ProjectService;
import com.contextlayer.backend.ranking.RankingResult.RankedFile;
import com.contextlayer.backend.ranking.RankingResult.Ranking;
import com.contextlayer.backend.ranking.RankingService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePackServiceTest {

	@TempDir
	Path tmp;

	Path root;
	ProjectService projectService = mock(ProjectService.class);
	RankingService rankingService = mock(RankingService.class);
	DependencyService dependencyService = mock(DependencyService.class);
	ProjectFileRepository fileRepository = mock(ProjectFileRepository.class);
	List<ProjectFile> indexed = new ArrayList<>();
	List<RankedFile> ranked = new ArrayList<>();

	@BeforeEach
	void setUp() throws IOException {
		root = Files.createDirectories(tmp.resolve("project")).toRealPath();
		Project project = Project.builder().name("Demo App").rootPath(root.toString()).build();
		when(projectService.getEntity(1L)).thenReturn(project);
		when(projectService.getEntity(99L)).thenThrow(new NotFoundException("Project 99 not found"));
		when(fileRepository.findAllByProjectId(1L)).thenAnswer(i -> indexed);
		when(rankingService.rank(eq(1L), anyString(), anyInt()))
			.thenAnswer(i -> new Ranking(List.of("email"), true, null, ranked));
		when(dependencyService.loadGraph(1L)).thenReturn(new DependencyGraph(List.of()));
	}

	FilePackService service() {
		return service(new FilePackProperties(100, 30, 8, 5, 20, 1024 * 1024, 20 * 1024 * 1024));
	}

	FilePackService service(FilePackProperties props) {
		return new FilePackService(
			projectService,
			rankingService,
			dependencyService,
			fileRepository,
			new TokenCounter(),
			props
		);
	}

	/** Creates the file on disk, lists it in the fake index and ranks it as a match for the task. */
	void file(String relative, String content) throws IOException {
		Path path = root.resolve(relative);
		Files.createDirectories(path.getParent());
		Files.writeString(path, content);
		indexed.add(ProjectFile.builder().relativePath(relative).sizeBytes(content.length()).build());
		ranked.add(
			new RankedFile(relative, 0.8, Map.of("retrieval", 0.5, "graph", 0.0), List.of("keyword match"), List.of())
		);
	}

	static Map<String, String> unzip(Path zip) throws IOException {
		return FilePackZipWriterTest.unzip(zip);
	}

	// ----- discovery -----

	@Test
	void discoverReturnsRankedFilesWithReasonAndNoDuplicates() throws IOException {
		file("src/RegisterService.java", "class RegisterService {}");
		ranked.add(ranked.get(0)); // the ranking returns the same file twice
		DiscoverResponse response = service().discover(1L, new DiscoverRequest("Add email verification", null));
		assertThat(response.files()).hasSize(1);
		assertThat(response.files().get(0).path()).isEqualTo("src/RegisterService.java");
		assertThat(response.files().get(0).reason()).isNotBlank();
		assertThat(response.disclaimer()).contains("not a guarantee");
		assertThat(response.totals().files()).isEqualTo(1);
	}

	@Test
	void discoverRejectsUnknownProject() {
		assertThatThrownBy(() -> service().discover(99L, new DiscoverRequest("task", null)))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void discoverRejectsBlankTask() {
		assertThatThrownBy(() -> service().discover(1L, new DiscoverRequest("   ", null)))
			.isInstanceOf(BadRequestException.class);
	}

	// ----- export: validation -----

	@Test
	void exportRejectsUnknownProject() {
		assertThatThrownBy(() -> service().export(99L, new ExportRequest("task", List.of("a.txt"))))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void exportRejectsEmptySelection() {
		assertThatThrownBy(() -> service().export(1L, new ExportRequest("task", List.of())))
			.isInstanceOf(BadRequestException.class)
			.hasMessageContaining("at least one file");
	}

	@Test
	void exportRejectsAbsoluteAndTraversalPaths() throws IOException {
		file("src/A.java", "class A {}");
		assertThatThrownBy(() -> service().export(1L, new ExportRequest("task", List.of("/etc/passwd"))))
			.isInstanceOf(BadRequestException.class);
		assertThatThrownBy(() -> service().export(1L, new ExportRequest("task", List.of("src/../../outside.txt"))))
			.isInstanceOf(BadRequestException.class);
	}

	@Test
	void exportRejectsFilesThatAreNotIndexed() throws IOException {
		file("src/A.java", "class A {}");
		Files.writeString(root.resolve("src/Other.java"), "class Other {}");
		assertThatThrownBy(() -> service().export(1L, new ExportRequest("task", List.of("src/Other.java"))))
			.isInstanceOf(BadRequestException.class)
			.hasMessageContaining("not part of the indexed project");
	}

	@Test
	void exportRejectsSensitiveFiles() throws IOException {
		file(".env", "DB_PASSWORD=abc");
		assertThatThrownBy(() -> service().export(1L, new ExportRequest("task", List.of(".env"))))
			.isInstanceOf(BadRequestException.class)
			.hasMessageContaining("cannot be exported");
	}

	@Test
	void exportRejectsSymbolicLinkEscape() throws IOException {
		file("src/A.java", "class A {}");
		Path outside = Files.createDirectories(tmp.resolve("outside"));
		Files.writeString(outside.resolve("secret.txt"), "top secret");
		try {
			Files.createSymbolicLink(root.resolve("src/leak.txt"), outside.resolve("secret.txt"));
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			assumeTrue(false, "Symbolic links are not available on this machine");
		}
		indexed.add(ProjectFile.builder().relativePath("src/leak.txt").sizeBytes(10).build()); // pretend it was indexed
		assertThatThrownBy(() -> service().export(1L, new ExportRequest("task", List.of("src/leak.txt"))))
			.isInstanceOf(BadRequestException.class)
			.hasMessageContaining("Symbolic");
	}

	@Test
	void exportEnforcesFileAndArchiveSizeLimits() throws IOException {
		file("src/A.java", "a".repeat(40));
		file("src/B.java", "b".repeat(40));
		var tinyFile = new FilePackProperties(100, 30, 8, 5, 20, 20, 20 * 1024 * 1024);
		assertThatThrownBy(() -> service(tinyFile).export(1L, new ExportRequest("task", List.of("src/A.java"))))
			.isInstanceOf(BadRequestException.class)
			.hasMessageContaining("too large");
		var tinyTotal = new FilePackProperties(100, 30, 8, 5, 20, 1024, 60);
		assertThatThrownBy(() ->
				service(tinyTotal).export(1L, new ExportRequest("task", List.of("src/A.java", "src/B.java")))
			)
			.isInstanceOf(BadRequestException.class)
			.hasMessageContaining("selection is too large");
	}

	// ----- export: success -----

	@Test
	void exportBuildsAZipWithOnlyTheSelectedFiles() throws IOException {
		file("src/main/A.java", "class A {}\n");
		file("src/main/util/B.java", "class B {}\n");
		file("src/main/C.java", "class C {}\n");
		ExportResult result = service()
			.export(
				1L,
				new ExportRequest(
					"Add email verification",
					List.of("src/main/A.java", "src/main/util/B.java", "src/main/A.java")
				)
			);
		try {
			Map<String, String> entries = unzip(result.file());
			assertThat(entries.keySet())
				.containsExactlyInAnyOrder(
					"files/src/main/A.java",
					"files/src/main/util/B.java",
					"manifest.json",
					"README.md"
				);
			assertThat(entries.get("files/src/main/A.java")).isEqualTo("class A {}\n");
			assertThat(entries.get("manifest.json")).contains("src/main/util/B.java").contains("\"category\"");
			assertThat(entries.get("README.md")).contains("Add email verification");
			assertThat(result.included()).isEqualTo(2);
			assertThat(result.fileName()).startsWith("file-pack-Demo-App-").endsWith(".zip");
		} finally {
			Files.deleteIfExists(result.file());
		}
	}

	@Test
	void exportLeavesOutFilesWithPossibleSecrets() throws IOException {
		file("src/A.java", "class A {}\n");
		file("config/db.properties", "db.password = \"hunter2hunter2\"\n");
		ExportResult result = service()
			.export(1L, new ExportRequest("task", List.of("src/A.java", "config/db.properties")));
		try {
			Map<String, String> entries = unzip(result.file());
			assertThat(entries).containsKey("files/src/A.java").doesNotContainKey("files/config/db.properties");
			assertThat(entries.get("manifest.json")).contains("config/db.properties").contains("possible secret");
			assertThat(result.excluded()).isEqualTo(1);
		} finally {
			Files.deleteIfExists(result.file());
		}
	}

	// ----- preview -----

	@Test
	void previewMasksSecretsAndRejectsUnsafePaths() throws IOException {
		file("config/db.properties", "db.password = \"hunter2hunter2\"\n");
		FilePreview preview = service().preview(1L, "config/db.properties");
		assertThat(preview.redactions()).isGreaterThan(0);
		assertThat(preview.content()).doesNotContain("hunter2hunter2");
		assertThatThrownBy(() -> service().preview(1L, "../outside.txt")).isInstanceOf(BadRequestException.class);
	}
}
