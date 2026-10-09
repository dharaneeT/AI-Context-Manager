package com.contextlayer.backend.graph;

import com.contextlayer.backend.common.PathUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PythonImportStrategy implements ImportStrategy {

	private static final Pattern IMPORT = Pattern.compile("^[ \\t]*import[ \\t]+([^#\\n]+)", Pattern.MULTILINE);
	private static final Pattern FROM = Pattern.compile(
		"^[ \\t]*from[ \\t]+(\\.*)([\\w.]*)[ \\t]+import[ \\t]+([^#\\n]+)",
		Pattern.MULTILINE
	);

	@Override
	public Set<String> languages() {
		return Set.of("python");
	}

	@Override
	public List<ResolvedImport> analyze(String filePath, String content, ProjectIndex index) {
		List<ResolvedImport> result = new ArrayList<>();

		Matcher im = IMPORT.matcher(content);
		while (im.find()) { // import a.b, c as d
			for (String part : im.group(1).split(",")) {
				String module = part.strip().split("\\s+")[0];
				if (module.isEmpty()) {
					continue;
				}
				String target = index.pythonModules().get(module);
				result.add(new ResolvedImport(module, target == null ? List.of() : List.of(target)));
			}
		}

		Matcher fm = FROM.matcher(content);
		while (fm.find()) { // from [.]a.b import c, d
			String dots = fm.group(1);
			String module = fm.group(2);
			List<String> names = names(fm.group(3));
			List<String> targets = new ArrayList<>();

			if (dots.isEmpty()) {
				addIfPresent(targets, index.pythonModules().get(module));
				for (String n : names) { // "from pkg import submodule"
					addIfPresent(targets, index.pythonModules().get(module + "." + n));
				}
			} else {
				String base = PathUtils.parentDir(filePath); // one dot = this file's package
				for (int i = 1; i < dots.length(); i++) {
					base = PathUtils.parentDir(base); // each extra dot goes one level up
				}
				String modPath = module.isEmpty() ? base : PathUtils.join(base, module.replace('.', '/'));
				if (!module.isEmpty()) {
					addFirstExisting(targets, index, modPath + ".py", modPath + "/__init__.py");
				}
				for (String n : names) {
					String sub = PathUtils.join(modPath, n);
					addFirstExisting(targets, index, sub + ".py", sub + "/__init__.py");
				}
			}
			String label = dots + module + (module.isEmpty() ? " (" + String.join(", ", names) + ")" : "");
			result.add(new ResolvedImport(label, targets));
		}
		return result;
	}

	private static List<String> names(String raw) {
		List<String> names = new ArrayList<>();
		for (String part : raw.replace("(", "").replace(")", "").replace("\\", "").split(",")) {
			String name = part.strip().split("\\s+")[0];
			if (!name.isEmpty() && !name.equals("*")) {
				names.add(name);
			}
		}
		return names;
	}

	private static void addIfPresent(List<String> list, String value) {
		if (value != null && !list.contains(value)) {
			list.add(value);
		}
	}

	private static void addFirstExisting(List<String> list, ProjectIndex index, String... candidates) {
		for (String c : candidates) {
			if (index.has(c)) {
				addIfPresent(list, c);
				return;
			}
		}
	}
}
