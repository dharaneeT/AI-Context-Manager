package com.contextlayer.backend.filepack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.contextlayer.backend.common.BadRequestException;
import com.contextlayer.backend.filepack.FilePackModel.Category;
import com.contextlayer.backend.filepack.FilePackModel.ExcludedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePackZipWriterTest {

	@TempDir
	Path tmp;

	static Map<String, String> unzip(Path zip) throws IOException {
		Map<String, String> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
		return entries;
	}

	@Test
	void keepsTheDirectoryStructureAndContentUnchanged() throws IOException {
		Path zip = tmp.resolve("pack.zip");
		String content = "package a;\r\nclass A {}\r\n"; // Windows line endings must survive
		try (FilePackZipWriter writer = new FilePackZipWriter(zip)) {
			writer.addFile(
				new FilePackZipWriter.Item("src/main/a/A.java", Category.CORE, 0.9, "match", content.length(), 6),
				content.getBytes(StandardCharsets.UTF_8)
			);
			writer.finish("Add email verification", Instant.now(), List.of());
		}
		Map<String, String> entries = unzip(zip);
		assertThat(entries).containsKeys("files/src/main/a/A.java", "manifest.json", "README.md");
		assertThat(entries.get("files/src/main/a/A.java")).isEqualTo(content);
	}

	@Test
	void writesManifestAndReadme() throws IOException {
		Path zip = tmp.resolve("pack.zip");
		try (FilePackZipWriter writer = new FilePackZipWriter(zip)) {
			writer.addFile(
				new FilePackZipWriter.Item("src/A.java", Category.CORE, 0.91, "says \"hi\"\nsecond line", 11, 3),
				"class A {}\n".getBytes(StandardCharsets.UTF_8)
			);
			writer.finish(
				"Add email verification",
				Instant.parse("2026-10-15T10:00:00Z"),
				List.of(new ExcludedFile("src/Key.java", "possible secret"))
			);
		}
		Map<String, String> entries = unzip(zip);
		String manifest = entries.get("manifest.json");
		assertThat(manifest)
			.contains("\"path\": \"src/A.java\"")
			.contains("\"category\": \"CORE\"")
			.contains("\"relevanceScore\": 0.9100")
			.contains("\"sizeBytes\": 11")
			.contains("\"estimatedTokens\": 3")
			.contains("says \\\"hi\\\"\\nsecond line") // JSON escaping works
			.contains("src/Key.java");
		String readme = entries.get("README.md");
		assertThat(readme)
			.contains("Add email verification")
			.contains("2026-10-15T10:00:00Z")
			.contains("Core implementation files")
			.contains("source code")
			.contains("Left out");
	}

	@Test
	void neverWritesTheSamePathTwice() throws IOException {
		Path zip = tmp.resolve("pack.zip");
		try (FilePackZipWriter writer = new FilePackZipWriter(zip)) {
			var item = new FilePackZipWriter.Item("src/A.java", Category.CORE, 0.5, "r", 1, 1);
			writer.addFile(item, new byte[] { 'a' });
			writer.addFile(item, new byte[] { 'a' });
			assertThat(writer.count()).isEqualTo(1);
			writer.finish("t", Instant.now(), List.of());
		}
		assertThat(unzip(zip).keySet()).containsExactly("files/src/A.java", "manifest.json", "README.md");
	}

	@Test
	void rejectsEntryPathsThatCouldEscapeTheArchive() throws IOException {
		try (FilePackZipWriter writer = new FilePackZipWriter(tmp.resolve("pack.zip"))) {
			for (String bad : new String[] { "../evil.txt", "/abs/evil.txt", "a/../../evil.txt", "C:/evil.txt" }) {
				assertThatThrownBy(() ->
						writer.addFile(new FilePackZipWriter.Item(bad, Category.CORE, 0, "r", 1, 1), new byte[] { 1 })
					)
					.as(bad)
					.isInstanceOf(BadRequestException.class);
			}
		}
	}
}
