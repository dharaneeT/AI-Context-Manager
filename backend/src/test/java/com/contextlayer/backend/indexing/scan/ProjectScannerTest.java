package com.contextlayer.backend.indexing.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextlayer.backend.config.IndexingProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.contextlayer.backend.indexing.LanguageDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectScannerTest {

	@TempDir
	Path root;

	ProjectScanner scanner;

	@BeforeEach
	void setUp() {
		var props = new IndexingProperties(
			1024,
			Set.of("node_modules", ".git"),
			Set.of("png"),
			Set.of("package-lock.json"),
			80,
			10,
			6000
		);
		scanner = new ProjectScanner(props, new LanguageDetector());
	}

	@Test
	void keepsSourceAndSkipsNoise() throws IOException {
		write("src/Main.java", "class Main {}");
		write("node_modules/lib/index.js", "x");
		write("logo.png", "fake");
		write("package-lock.json", "{}");
		write("big.txt", "x".repeat(2000));
		write("empty.txt", "");
		Files.write(root.resolve("data.bin"), new byte[] { 1, 0, 2 });

		ScanResult result = scanner.scan(root);

		assertThat(result.files()).extracting(ScannedFile::relativePath).containsExactly("src/Main.java");
		assertThat(result.files().get(0).language()).isEqualTo("java");
		assertThat(result.skipped())
			.containsKeys(
				"ignored-directory",
				"ignored-extension",
				"ignored-file-name",
				"too-large",
				"empty",
				"binary-content"
			);
	}

	@Test
	void respectsGitignore() throws IOException {
		write(".gitignore", "build/\n*.log\n# comment\n");
		write("build/out.txt", "x");
		write("app.log", "x");
		write("src/debug.log", "x");
		write("src/Main.java", "class Main {}");

		ScanResult result = scanner.scan(root);

		assertThat(result.files())
			.extracting(ScannedFile::relativePath)
			.containsExactlyInAnyOrder(".gitignore", "src/Main.java");
	}

	private void write(String relative, String content) throws IOException {
		Path p = root.resolve(relative);
		Files.createDirectories(p.getParent());
		Files.writeString(p, content);
	}
}
