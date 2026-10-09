package com.contextlayer.backend.graph;

import com.contextlayer.backend.common.PathUtils;
import com.contextlayer.backend.indexing.ProjectFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lookup tables built once per graph build, so resolving an import is a map lookup, not a scan.
 *
 * @param paths             every indexed file path
 * @param javaTypeToPath    "com.x.Foo" -> "src/.../Foo.java" (from each file's package declaration)
 * @param javaPackageToPaths "com.x" -> all files in that package
 * @param pythonModules     "app.utils.db" (and every shorter suffix, e.g. "utils.db") -> file path
 */
public record ProjectIndex(
	Set<String> paths,
	Map<String, String> javaTypeToPath,
	Map<String, List<String>> javaPackageToPaths,
	Map<String, String> pythonModules
) {
	private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)", Pattern.MULTILINE);

	public boolean has(String path) {
		return paths.contains(path);
	}

	public static ProjectIndex build(Path root, Map<String, ProjectFile> byPath) {
		Set<String> paths = new TreeSet<>(byPath.keySet());
		Map<String, String> javaTypes = new HashMap<>();
		Map<String, List<String>> javaPackages = new HashMap<>();
		Map<String, String> pythonModules = new HashMap<>();

		for (Map.Entry<String, ProjectFile> entry : new java.util.TreeMap<>(byPath).entrySet()) {
			String path = entry.getKey();
			String language = entry.getValue().getLanguage();

			if ("java".equals(language) || "kotlin".equals(language)) {
				String pkg = readPackage(root.resolve(path));
				String fqn = pkg.isEmpty() ? PathUtils.stem(path) : pkg + "." + PathUtils.stem(path);
				javaTypes.putIfAbsent(fqn, path);
				javaPackages.computeIfAbsent(pkg, k -> new ArrayList<>()).add(path);
			} else if ("python".equals(language) && path.endsWith(".py")) {
				List<String> segments = new ArrayList<>(List.of(path.substring(0, path.length() - 3).split("/")));
				if (segments.get(segments.size() - 1).equals("__init__")) {
					segments.remove(segments.size() - 1);
				}
				// Register every suffix, because the Python source root is unknown:
				// "src/app/utils/db.py" answers to "src.app.utils.db", "app.utils.db", "utils.db" and "db".
				for (int i = 0; i < segments.size(); i++) {
					pythonModules.putIfAbsent(String.join(".", segments.subList(i, segments.size())), path);
				}
			}
		}
		return new ProjectIndex(paths, javaTypes, javaPackages, pythonModules);
	}

	private static String readPackage(Path file) {
		try {
			String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			Matcher m = PACKAGE.matcher(content);
			return m.find() ? m.group(1) : "";
		} catch (IOException e) {
			return "";
		}
	}
}
