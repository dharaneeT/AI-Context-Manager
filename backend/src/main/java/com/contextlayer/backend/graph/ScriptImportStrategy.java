package com.contextlayer.backend.graph;

import com.contextlayer.backend.common.PathUtils;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ScriptImportStrategy implements ImportStrategy {

	// import x from 'y' | import 'y' | export * from 'y' | import type {A} from 'y' (multi-line ok)
	private static final Pattern FROM = Pattern.compile(
		"(?:import|export)\\s+(?:[^'\";]*?\\s+from\\s+)?['\"]([^'\"]+)['\"]"
	);
	// require('y') and dynamic import('y')
	private static final Pattern CALL = Pattern.compile("(?:require|import)\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
	private static final List<String> EXTENSIONS = List.of(
		"ts",
		"tsx",
		"js",
		"jsx",
		"mjs",
		"cjs",
		"json",
		"css",
		"scss"
	);

	@Override
	public Set<String> languages() {
		return Set.of("javascript", "typescript");
	}

	@Override
	public List<ResolvedImport> analyze(String filePath, String content, ProjectIndex index) {
		Set<String> specifiers = new LinkedHashSet<>();
		for (Pattern p : List.of(FROM, CALL)) {
			Matcher m = p.matcher(content);
			while (m.find()) {
				specifiers.add(m.group(1));
			}
		}
		List<ResolvedImport> result = new ArrayList<>();
		for (String spec : specifiers) {
			List<String> targets = new ArrayList<>();
			String resolved = resolve(spec, filePath, index);
			if (resolved != null) {
				targets.add(resolved);
			}
			result.add(new ResolvedImport(spec, targets));
		}
		return result;
	}

	private static String resolve(String spec, String filePath, ProjectIndex index) {
		String base;
		if (spec.startsWith(".")) {
			base = PathUtils.join(PathUtils.parentDir(filePath), spec);
		} else if (spec.startsWith("@/") || spec.startsWith("~/")) {
			String srcRoot = srcRoot(filePath); // the common "@ = src" alias
			if (srcRoot == null) {
				return null;
			}
			base = PathUtils.join(srcRoot, spec.substring(2));
		} else {
			return null; // bare specifier = npm package = external
		}

		List<String> candidates = new ArrayList<>();
		candidates.add(base);
		for (String ext : EXTENSIONS) {
			candidates.add(base + "." + ext);
			candidates.add(base + "/index." + ext);
		}
		// TypeScript ESM writes "./foo.js" for a file that is really foo.ts
		if (base.endsWith(".js")) {
			String stem = base.substring(0, base.length() - 3);
			candidates.add(stem + ".ts");
			candidates.add(stem + ".tsx");
		}
		for (String candidate : candidates) {
			if (index.has(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	/** "frontend/src/a/B.jsx" -> "frontend/src"; "src/B.js" -> "src". */
	private static String srcRoot(String filePath) {
		if (filePath.startsWith("src/")) {
			return "src";
		}
		int i = filePath.indexOf("/src/");
		return i < 0 ? null : filePath.substring(0, i + 4);
	}
}
