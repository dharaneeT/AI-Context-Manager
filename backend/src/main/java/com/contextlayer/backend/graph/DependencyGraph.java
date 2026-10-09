package com.contextlayer.backend.graph;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** The whole graph as two adjacency maps. Ranking asks "how close is file X to the best matches?" thousands of times. */
public final class DependencyGraph {

	private final Map<String, Set<String>> outgoing = new HashMap<>();
	private final Map<String, Set<String>> incoming = new HashMap<>();

	public DependencyGraph(Collection<GraphEdge> edges) {
		for (GraphEdge e : edges) {
			outgoing.computeIfAbsent(e.from(), k -> new TreeSet<>()).add(e.to());
			incoming.computeIfAbsent(e.to(), k -> new TreeSet<>()).add(e.from());
		}
	}

	public Set<String> dependenciesOf(String path) {
		return outgoing.getOrDefault(path, Set.of());
	}

	public Set<String> dependentsOf(String path) {
		return incoming.getOrDefault(path, Set.of());
	}

	/** Breadth-first search following edges in BOTH directions. Returns path -> hops from start (start = 0). */
	public Map<String, Integer> neighborhood(String start, int maxDepth) {
		Map<String, Integer> distance = new LinkedHashMap<>();
		distance.put(start, 0);
		Deque<String> queue = new ArrayDeque<>();
		queue.add(start);
		while (!queue.isEmpty()) {
			String current = queue.poll();
			int d = distance.get(current);
			if (d >= maxDepth) {
				continue;
			}
			for (String next : union(current)) {
				if (distance.putIfAbsent(next, d + 1) == null) {
					queue.add(next);
				}
			}
		}
		return distance;
	}

	private Set<String> union(String path) {
		Set<String> all = new TreeSet<>(dependenciesOf(path));
		all.addAll(dependentsOf(path));
		return all;
	}
}
