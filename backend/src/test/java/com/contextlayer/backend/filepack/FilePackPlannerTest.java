package com.contextlayer.backend.filepack;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextlayer.backend.filepack.FilePackModel.Category;
import com.contextlayer.backend.filepack.FilePackModel.RecommendedFile;
import com.contextlayer.backend.graph.DependencyGraph;
import com.contextlayer.backend.graph.DependencyKind;
import com.contextlayer.backend.graph.GraphEdge;
import com.contextlayer.backend.ranking.RankingResult.RankedFile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FilePackPlannerTest {

	private static final FilePackPlanner.Settings SETTINGS = new FilePackPlanner.Settings(100, 8, 5, 20);

	private static RankedFile ranked(String path, double score, double retrieval, String reason) {
		return new RankedFile(path, score, Map.of("retrieval", retrieval, "graph", 0.0), List.of(reason), List.of());
	}

	private static Map<String, RecommendedFile> byPath(FilePackPlanner.Plan plan) {
		Map<String, RecommendedFile> map = new HashMap<>();
		plan.files().forEach(f -> map.put(f.path(), f));
		return map;
	}

	private static final Map<String, Long> INDEXED = Map.of(
		"src/Register.java",
		400L,
		"src/test/RegisterTest.java",
		200L,
		"src/Far.java",
		80L,
		"src/Mail.java",
		120L,
		".env",
		5L
	);

	@Test
	void categorisesCoreSupportingDependencyAndContextFiles() {
		DependencyGraph graph = new DependencyGraph(
			List.of(new GraphEdge("src/Register.java", "src/Mail.java", DependencyKind.IMPORT))
		);
		var plan = FilePackPlanner.plan(
			List.of(
				ranked("src/Register.java", 0.9, 0.5, "keyword match"),
				ranked("src/test/RegisterTest.java", 0.7, 0.4, "test for Register"),
				ranked("src/Far.java", 0.2, 0.0, "changed together")
			),
			graph,
			INDEXED,
			SETTINGS
		);
		Map<String, RecommendedFile> files = byPath(plan);
		assertThat(files.get("src/Register.java").category()).isEqualTo(Category.CORE);
		assertThat(files.get("src/test/RegisterTest.java").category()).isEqualTo(Category.SUPPORTING);
		assertThat(files.get("src/Mail.java").category()).isEqualTo(Category.DEPENDENCY);
		assertThat(files.get("src/Mail.java").reason()).contains("Imported by src/Register.java");
		assertThat(files.get("src/Far.java").category()).isEqualTo(Category.CONTEXT_ONLY);
		assertThat(files.get("src/Far.java").selectedByDefault()).isFalse();
		assertThat(files.get("src/Register.java").selectedByDefault()).isTrue();
		assertThat(files.get("src/Register.java").reason()).isNotBlank();
	}

	@Test
	void removesDuplicatePaths() {
		var plan = FilePackPlanner.plan(
			List.of(
				ranked("src/Register.java", 0.9, 0.5, "first"),
				ranked("src/Register.java", 0.8, 0.5, "second"),
				ranked("./src/Register.java", 0.7, 0.5, "third, same file written differently")
			),
			new DependencyGraph(List.of()),
			INDEXED,
			SETTINGS
		);
		assertThat(plan.files()).extracting(RecommendedFile::path).containsExactly("src/Register.java");
	}

	@Test
	void hidesSensitiveFilesAndIgnoresFilesThatAreNotIndexed() {
		var plan = FilePackPlanner.plan(
			List.of(
				ranked(".env", 0.9, 0.5, "matched"),
				ranked("src/Ghost.java", 0.8, 0.5, "not in the index"),
				ranked("src/Register.java", 0.5, 0.5, "ok")
			),
			new DependencyGraph(List.of()),
			INDEXED,
			SETTINGS
		);
		assertThat(plan.files()).extracting(RecommendedFile::path).containsExactly("src/Register.java");
		assertThat(plan.hiddenSensitive()).isEqualTo(1);
	}

	@Test
	void respectsTheMaximumNumberOfFiles() {
		var plan = FilePackPlanner.plan(
			List.of(
				ranked("src/Register.java", 0.9, 0.5, "a"),
				ranked("src/Far.java", 0.8, 0.5, "b"),
				ranked("src/Mail.java", 0.7, 0.5, "c")
			),
			new DependencyGraph(List.of()),
			INDEXED,
			new FilePackPlanner.Settings(2, 8, 5, 20)
		);
		assertThat(plan.files()).hasSize(2);
	}
}
