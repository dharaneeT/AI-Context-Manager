package com.contextlayer.backend.ranking;

import com.contextlayer.backend.common.BadRequestException;
import com.contextlayer.backend.config.RankingProperties;
import com.contextlayer.backend.git.GitException;
import com.contextlayer.backend.git.GitFileStats;
import com.contextlayer.backend.git.GitFileStatsRepository;
import com.contextlayer.backend.git.GitIntelligenceService;
import com.contextlayer.backend.git.GitResponses.CoChange;
import com.contextlayer.backend.git.GitStatusParser.WorkingChange;
import com.contextlayer.backend.graph.DependencyGraph;
import com.contextlayer.backend.graph.DependencyService;
import com.contextlayer.backend.indexing.ProjectFileRepository;
import com.contextlayer.backend.ranking.RankingResult.RankedFile;
import com.contextlayer.backend.ranking.RankingResult.Ranking;
import com.contextlayer.backend.search.HybridResults.HybridChunk;
import com.contextlayer.backend.search.HybridResults.HybridFile;
import com.contextlayer.backend.search.HybridResults.HybridResult;
import com.contextlayer.backend.search.HybridSearchService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RankingService {

	private final HybridSearchService hybridSearch;
	private final DependencyService dependencyService;
	private final GitIntelligenceService gitService;
	private final GitFileStatsRepository gitStatsRepository;
	private final ProjectFileRepository fileRepository;
	private final RankingProperties props;

	public Ranking rank(Long projectId, String task, int maxFiles) {
		if (task == null || task.isBlank()) {
			throw new BadRequestException("task must not be blank");
		}
		HybridResult hybrid = hybridSearch.search(projectId, task, props.candidateFiles());
		Set<String> indexed = new HashSet<>(fileRepository.findAllPaths(projectId));
		Map<String, List<String>> reasons = new HashMap<>();

		// 1. retrieval, normalised so the best hit is 1.0
		double top = hybrid.files().stream().mapToDouble(HybridFile::score).max().orElse(0);
		Map<String, Double> retrieval = new LinkedHashMap<>();
		for (HybridFile f : hybrid.files()) {
			retrieval.put(f.path(), top > 0 ? f.score() / top : 0);
			reasons.computeIfAbsent(f.path(), k -> new ArrayList<>()).addAll(f.reasons());
		}

		// 2. seeds: the best matches. Graph and co-change are measured relative to these.
		List<String> seeds = retrieval
			.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Double>comparingByValue().reversed())
			.limit(props.seedCount())
			.map(Map.Entry::getKey)
			.toList();

		// 3. graph proximity
		DependencyGraph graph = dependencyService.loadGraph(projectId);
		Map<String, Double> graphScore = new HashMap<>();
		Map<String, String> graphReason = new HashMap<>();
		for (String seed : seeds) {
			double w = retrieval.get(seed);
			for (Map.Entry<String, Integer> e : graph.neighborhood(seed, props.graphDepth()).entrySet()) {
				String path = e.getKey();
				if (e.getValue() == 0 || !indexed.contains(path)) {
					continue;
				}
				double s = FileScorer.graph(e.getValue(), w, props.graphDecay());
				if (s > graphScore.getOrDefault(path, 0.0)) {
					graphScore.put(path, s);
					graphReason.put(path, describeLink(graph, seed, path, e.getValue()));
				}
			}
		}
		graphReason.forEach((path, why) -> reasons.computeIfAbsent(path, k -> new ArrayList<>()).add(why));

		// 4. co-change: files edited in the same commits as a seed
		Map<String, Double> coChange = new HashMap<>();
		for (String seed : seeds) {
			List<CoChange> partners = gitService.coChanges(projectId, seed, 10);
			int maxPair = partners.stream().mapToInt(CoChange::count).max().orElse(0);
			for (CoChange c : partners) {
				if (!indexed.contains(c.path())) {
					continue;
				}
				double s = retrieval.get(seed) * c.count() / maxPair;
				if (s > coChange.getOrDefault(c.path(), 0.0)) {
					coChange.put(c.path(), s);
					reasons
						.computeIfAbsent(c.path(), k -> new ArrayList<>())
						.add("changed together with " + seed + " in " + c.count() + " commits");
				}
			}
		}

		// 5. git facts and uncommitted work (both optional: the project may not be a repo)
		Map<String, GitFileStats> stats = gitStatsRepository
			.findByProjectId(projectId)
			.stream()
			.collect(Collectors.toMap(GitFileStats::getRelativePath, s -> s, (a, b) -> a));
		int maxCommits = stats.values().stream().mapToInt(GitFileStats::getCommitCount).max().orElse(0);
		Set<String> working = workingPaths(projectId);

		// 6. candidates: ONLY files with real evidence (match, graph link, or co-change)
		Set<String> candidates = new LinkedHashSet<>(retrieval.keySet());
		candidates.addAll(graphScore.keySet());
		candidates.addAll(coChange.keySet());

		Instant now = Instant.now();
		List<RankedFile> ranked = new ArrayList<>();
		Map<String, List<HybridChunk>> chunksByPath = hybrid
			.chunks()
			.stream()
			.collect(Collectors.groupingBy(HybridChunk::path, LinkedHashMap::new, Collectors.toList()));

		for (String path : candidates) {
			GitFileStats st = stats.get(path);
			double rec = st == null ? 0 : FileScorer.recency(st.getLastCommitAt(), now, props.recencyHalfLifeDays());
			FileSignals signals = new FileSignals(
				path,
				retrieval.getOrDefault(path, 0.0),
				graphScore.getOrDefault(path, 0.0),
				coChange.getOrDefault(path, 0.0),
				rec,
				working.contains(path) ? 1 : 0,
				st == null ? 0 : FileScorer.churn(st.getCommitCount(), maxCommits)
			);

			List<String> why = reasons.computeIfAbsent(path, k -> new ArrayList<>());
			if (st != null && rec >= 0.25) {
				why.add(
					"changed " +
					Duration.between(st.getLastCommitAt(), now).toDays() +
					" day(s) ago" +
					(st.getCommitsLast30d() > 0 ? " (" + st.getCommitsLast30d() + " commits in 30 days)" : "")
				);
			}
			if (signals.working() > 0) {
				why.add("has uncommitted changes");
			}

			Map<String, Double> components = new LinkedHashMap<>();
			FileScorer.contributions(signals, props).forEach((k, v) -> components.put(k, round(v)));
			ranked.add(
				new RankedFile(
					path,
					round(FileScorer.score(signals, props)),
					components,
					why,
					chunksByPath.getOrDefault(path, List.of())
				)
			);
		}

		ranked.sort(Comparator.comparingDouble(RankedFile::score).reversed().thenComparing(RankedFile::path));
		return new Ranking(
			hybrid.keywords(),
			hybrid.semanticUsed(),
			hybrid.semanticNote(),
			ranked.stream().limit(Math.max(1, maxFiles)).toList()
		);
	}

	private Set<String> workingPaths(Long projectId) {
		try {
			return gitService.workingChanges(projectId).stream().map(WorkingChange::path).collect(Collectors.toSet());
		} catch (BadRequestException | GitException e) {
			return Set.of();
		}
	}

	private static String describeLink(DependencyGraph g, String seed, String file, int hops) {
		if (hops == 1) {
			return g.dependenciesOf(file).contains(seed)
				? "imports/uses top match " + seed
				: "is imported/used by top match " + seed;
		}
		return hops + " hops from top match " + seed;
	}

	private static double round(double v) {
		return Math.round(v * 10_000d) / 10_000d;
	}
}
