package com.contextlayer.backend.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DependencyGraphTest {

	@Test
	void neighborhoodFollowsBothDirections() {
		var graph = new DependencyGraph(
			List.of(
				new GraphEdge("A", "B", DependencyKind.IMPORT),
				new GraphEdge("B", "C", DependencyKind.IMPORT),
				new GraphEdge("D", "A", DependencyKind.IMPORT)
			)
		);

		assertThat(graph.neighborhood("A", 1)).containsOnlyKeys("A", "B", "D");
		assertThat(graph.neighborhood("A", 2)).containsEntry("C", 2);
		assertThat(graph.dependentsOf("A")).containsExactly("D");
	}
}
