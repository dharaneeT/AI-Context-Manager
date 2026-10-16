package com.contextlayer.backend.filepack;

import com.contextlayer.backend.common.PathUtils;
import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.filepack.FilePackModel.Category;
import com.contextlayer.backend.filepack.FilePackModel.RecommendedFile;
import com.contextlayer.backend.graph.DependencyGraph;
import com.contextlayer.backend.ranking.RankingResult.RankedFile;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns the existing ranking (RankingService) plus the dependency graph into a recommended file list.
 * Pure logic: no database, no disk. That keeps it easy to test.
 */
public final class FilePackPlanner {

	public record Settings(int maxFiles, int coreFiles, int expandTopFiles, int maxExtraDependencies) {}

	public record Plan(List<RecommendedFile> files, int hiddenSensitive) {}

	private record Entry(String path, Category category, double score, String reason) {}

	private static final Set<String> TEST_DIRECTORIES = Set.of("test", "tests", "__tests__", "spec", "specs", "e2e");
	private static final Set<String> CONFIG_EXTENSIONS = Set.of(
		"properties",
		"yml",
		"yaml",
		"toml",
		"ini",
		"cfg",
		"conf"
	);
	private static final Set<String> CONFIG_NAMES = Set.of(
		"pom.xml",
		"package.json",
		"tsconfig.json",
		"dockerfile",
		".gitignore",
		".dockerignore",
		"build.gradle",
		"settings.gradle",
		"vite.config.js",
		"eslint.config.js"
	);

	private FilePackPlanner() {}

	/**
	 * @param ranked       files from RankingService, best first
	 * @param indexedSizes every indexed path with its size in bytes; anything else is ignored
	 */
	public static Plan plan(
		List<RankedFile> ranked,
		DependencyGraph graph,
		Map<String, Long> indexedSizes,
		Settings settings
	) {
		Map<String, Entry> found = new LinkedHashMap<>(); // keyed by normalised path, so duplicates collapse
		int hidden = 0;
		int cores = 0;

		List<RankedFile> ordered = ranked
			.stream()
			.sorted(Comparator.comparingDouble(RankedFile::score).reversed().thenComparing(RankedFile::path))
			.toList();
		for (RankedFile f : ordered) {
			String path = PathUtils.normalize(f.path());
			if (path.isEmpty() || !indexedSizes.containsKey(path) || found.containsKey(path)) {
				continue;
			}
			if (SafeProjectFiles.blockedReason(path) != null) {
				hidden++;
				continue;
			}
			double retrieval = f.components() == null ? 0 : f.components().getOrDefault("retrieval", 0.0);
			Category category;
			if (isSupporting(path)) {
				category = Category.SUPPORTING;
			} else if (retrieval > 0 && cores < settings.coreFiles()) {
				category = Category.CORE;
				cores++;
			} else {
				category = Category.CONTEXT_ONLY;
			}
			found.put(path, new Entry(path, category, f.score(), reasonOf(f)));
		}

		// Dependencies: what the best core files import.
		List<Entry> topCore = found
			.values()
			.stream()
			.filter(e -> e.category() == Category.CORE)
			.limit(settings.expandTopFiles())
			.toList();
		int extra = 0;
		for (Entry core : topCore) {
			for (String raw : graph.dependenciesOf(core.path())) {
				String dep = PathUtils.normalize(raw);
				if (!indexedSizes.containsKey(dep)) {
					continue;
				}
				if (SafeProjectFiles.blockedReason(dep) != null) {
					hidden++;
					continue;
				}
				String why = "Imported by " + core.path();
				Entry existing = found.get(dep);
				if (existing == null) {
					if (extra >= settings.maxExtraDependencies()) {
						continue;
					}
					extra++;
					Category category = isSupporting(dep) ? Category.SUPPORTING : Category.DEPENDENCY;
					found.put(
						dep,
						new Entry(dep, category, round(core.score() * 0.5), why + " (added from the dependency graph)")
					);
				} else if (existing.category() == Category.CONTEXT_ONLY) {
					found.put(
						dep,
						new Entry(dep, Category.DEPENDENCY, existing.score(), why + "; " + existing.reason())
					);
				}
			}
		}

		List<RecommendedFile> files = found
			.values()
			.stream()
			.sorted(
				Comparator
					.<Entry>comparingInt(e -> e.category().ordinal())
					.thenComparing(Comparator.comparingDouble(Entry::score).reversed())
					.thenComparing(Entry::path)
			)
			.limit(settings.maxFiles())
			.map(e -> {
				long size = indexedSizes.get(e.path());
				return new RecommendedFile(
					e.path(),
					e.category(),
					e.category().label(),
					e.score(),
					e.reason(),
					size,
					estimateTokens(size),
					e.category() != Category.CONTEXT_ONLY // context-only files are offered, not pre-selected
				);
			})
			.toList();
		return new Plan(files, hidden);
	}

	/** Same "about 4 characters per token" rule of thumb as the indexer. Exact counts are used in the ZIP manifest. */
	static int estimateTokens(long sizeBytes) {
		return (int) ((sizeBytes + 3) / 4);
	}

	static boolean isSupporting(String path) {
		String lower = path.toLowerCase(Locale.ROOT);
		String[] parts = lower.split("/");
		for (int i = 0; i < parts.length - 1; i++) {
			if (TEST_DIRECTORIES.contains(parts[i])) {
				return true;
			}
		}
		String name = parts[parts.length - 1];
		if (
			name.endsWith("test.java") ||
			name.endsWith("tests.java") ||
			name.startsWith("test_") ||
			name.endsWith("_test.py") ||
			name.matches(".*\\.(test|spec)\\.[a-z]+")
		) {
			return true;
		}
		int dot = name.lastIndexOf('.');
		String ext = dot <= 0 ? "" : name.substring(dot + 1);
		return (
			CONFIG_NAMES.contains(name) ||
			CONFIG_EXTENSIONS.contains(ext) ||
			name.startsWith("docker-compose") ||
			name.matches(".*\\.config\\.(js|mjs|cjs|ts)")
		);
	}

	private static String reasonOf(RankedFile f) {
		String joined = f.reasons() == null
			? ""
			: f.reasons().stream().distinct().limit(3).collect(Collectors.joining("; "));
		return joined.isBlank() ? "Matched the task description" : Text.truncate(joined, 300);
	}

	private static double round(double v) {
		return Math.round(v * 10_000d) / 10_000d;
	}
}
