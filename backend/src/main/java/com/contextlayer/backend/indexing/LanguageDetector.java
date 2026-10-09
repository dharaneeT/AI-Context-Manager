package com.contextlayer.backend.indexing;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LanguageDetector {

	private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
		Map.entry("java", "java"),
		Map.entry("kt", "kotlin"),
		Map.entry("js", "javascript"),
		Map.entry("jsx", "javascript"),
		Map.entry("mjs", "javascript"),
		Map.entry("ts", "typescript"),
		Map.entry("tsx", "typescript"),
		Map.entry("py", "python"),
		Map.entry("go", "go"),
		Map.entry("rs", "rust"),
		Map.entry("cs", "csharp"),
		Map.entry("c", "c"),
		Map.entry("h", "c"),
		Map.entry("cpp", "cpp"),
		Map.entry("rb", "ruby"),
		Map.entry("php", "php"),
		Map.entry("swift", "swift"),
		Map.entry("sql", "sql"),
		Map.entry("html", "html"),
		Map.entry("css", "css"),
		Map.entry("scss", "css"),
		Map.entry("json", "json"),
		Map.entry("yml", "yaml"),
		Map.entry("yaml", "yaml"),
		Map.entry("xml", "xml"),
		Map.entry("md", "markdown"),
		Map.entry("properties", "properties"),
		Map.entry("sh", "shell"),
		Map.entry("gradle", "gradle"),
		Map.entry("toml", "toml")
	);

	private static final Map<String, String> BY_FILE_NAME = Map.of("Dockerfile", "docker", "Makefile", "make");

	public String detect(String fileName, String extension) {
		String byName = BY_FILE_NAME.get(fileName);
		if (byName != null) {
			return byName;
		}
		return BY_EXTENSION.getOrDefault(extension, "unknown");
	}
}
