package com.contextlayer.backend.graph;

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
class DependencyServiceTest {

	@Autowired
	ProjectService projectService;

	@Autowired
	IndexingService indexingService;

	@Autowired
	DependencyService dependencyService;

	@Autowired
	ProjectRepository projectRepository;

	@TempDir
	Path dir;

	@AfterEach
	void tearDown() {
		projectRepository.deleteAll();
	}

	@Test
	void buildsEdgesBetweenProjectFiles() throws Exception {
		Files.createDirectories(dir.resolve("a"));
		Files.createDirectories(dir.resolve("b"));
		Files.writeString(
			dir.resolve("a/Foo.java"),
			"package a;\nimport b.Bar;\nimport java.util.List;\npublic class Foo {}\n"
		);
		Files.writeString(dir.resolve("b/Bar.java"), "package b;\npublic class Bar {}\n");

		Long id = projectService.create(new CreateProjectRequest("g", dir.toString(), null)).id();
		indexingService.indexProject(id);
		GraphBuildResult result = dependencyService.build(id);

		assertThat(result.resolvedEdges()).isEqualTo(1);
		assertThat(result.externalImports()).isEqualTo(1); // java.util.List
		assertThat(dependencyService.dependenciesOf(id, "a/Foo.java")).containsExactly("b/Bar.java");
		assertThat(dependencyService.dependentsOf(id, "b/Bar.java")).containsExactly("a/Foo.java");
	}

	@Test
	void addsReferenceEdgesForSamePackageTypes() throws Exception {
		Files.createDirectories(dir.resolve("p"));
		Files.writeString(dir.resolve("p/Service.java"), "package p;\npublic class Service { Repo repo; }\n");
		Files.writeString(dir.resolve("p/Repo.java"), "package p;\npublic class Repo {}\n");

		Long id = projectService.create(new CreateProjectRequest("g", dir.toString(), null)).id();
		indexingService.indexProject(id);
		GraphBuildResult result = dependencyService.build(id);

		assertThat(result.referenceEdges()).isEqualTo(1);
		assertThat(dependencyService.dependenciesOf(id, "p/Service.java")).containsExactly("p/Repo.java");
	}
}
