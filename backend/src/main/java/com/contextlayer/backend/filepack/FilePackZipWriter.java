package com.contextlayer.backend.filepack;

import com.contextlayer.backend.filepack.FilePackModel.Category;
import com.contextlayer.backend.filepack.FilePackModel.ExcludedFile;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes the Developer File Pack straight to a file on disk, one entry at a time, so the whole archive never
 * has to sit in memory. Layout inside the ZIP:
 * <pre>
 *   README.md
 *   manifest.json
 *   files/&lt;original relative path&gt;      (selected files, byte-for-byte unchanged)
 * </pre>
 */
public final class FilePackZipWriter implements AutoCloseable {

	public static final String FILES_DIR = "files/";

	public record Item(
		String path,
		Category category,
		double score,
		String reason,
		long sizeBytes,
		int estimatedTokens
	) {}

	private final ZipOutputStream zip;
	private final Set<String> entryNames = new HashSet<>();
	private final List<Item> items = new ArrayList<>();

	public FilePackZipWriter(Path target) throws IOException {
		this.zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target)), StandardCharsets.UTF_8);
	}

	public int count() {
		return items.size();
	}

	/** The same path is never written twice. The path is validated again, so "../x" can never become an entry. */
	public void addFile(Item item, byte[] content) throws IOException {
		String entry = entryName(item.path());
		if (!entryNames.add(entry)) {
			return;
		}
		zip.putNextEntry(new ZipEntry(entry));
		zip.write(content);
		zip.closeEntry();
		items.add(item);
	}

	static String entryName(String relativePath) {
		return FILES_DIR + SafeProjectFiles.requireRelative(relativePath);
	}

	/** Writes manifest.json and README.md and finishes the archive. */
	public void finish(String task, Instant exportedAt, List<ExcludedFile> excluded) throws IOException {
		writeText("manifest.json", manifest(task, exportedAt, excluded));
		writeText("README.md", readme(task, exportedAt, excluded));
		zip.finish();
	}

	@Override
	public void close() throws IOException {
		zip.close();
	}

	private void writeText(String name, String text) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(text.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	String manifest(String task, Instant exportedAt, List<ExcludedFile> excluded) {
		long bytes = items.stream().mapToLong(Item::sizeBytes).sum();
		long tokens = items.stream().mapToLong(Item::estimatedTokens).sum();
		StringBuilder sb = new StringBuilder("{\n");
		sb.append("  \"task\": ").append(json(task)).append(",\n");
		sb.append("  \"exportedAt\": ").append(json(exportedAt.toString())).append(",\n");
		sb.append("  \"note\": ").append(json(FilePackService.DISCLAIMER)).append(",\n");
		sb
			.append("  \"totals\": {\"files\": ")
			.append(items.size())
			.append(", \"sizeBytes\": ")
			.append(bytes)
			.append(", \"estimatedTokens\": ")
			.append(tokens)
			.append("},\n");
		sb.append("  \"files\": [");
		for (int i = 0; i < items.size(); i++) {
			Item it = items.get(i);
			sb.append(i == 0 ? "\n" : ",\n");
			sb
				.append("    {\"path\": ")
				.append(json(it.path()))
				.append(", \"archivePath\": ")
				.append(json(FILES_DIR + it.path()))
				.append(", \"category\": ")
				.append(json(it.category().name()))
				.append(", \"relevanceScore\": ")
				.append(String.format(Locale.ROOT, "%.4f", it.score()))
				.append(", \"reason\": ")
				.append(json(it.reason()))
				.append(", \"sizeBytes\": ")
				.append(it.sizeBytes())
				.append(", \"estimatedTokens\": ")
				.append(it.estimatedTokens())
				.append("}");
		}
		sb.append(items.isEmpty() ? "],\n" : "\n  ],\n");
		sb.append("  \"excluded\": [");
		for (int i = 0; i < excluded.size(); i++) {
			sb.append(i == 0 ? "\n" : ",\n");
			sb
				.append("    {\"path\": ")
				.append(json(excluded.get(i).path()))
				.append(", \"reason\": ")
				.append(json(excluded.get(i).reason()))
				.append("}");
		}
		sb.append(excluded.isEmpty() ? "]\n" : "\n  ]\n");
		return sb.append("}\n").toString();
	}

	String readme(String task, Instant exportedAt, List<ExcludedFile> excluded) {
		long bytes = items.stream().mapToLong(Item::sizeBytes).sum();
		long tokens = items.stream().mapToLong(Item::estimatedTokens).sum();
		StringBuilder sb = new StringBuilder("# Developer File Pack\n\n");
		sb.append("**Task:**\n\n");
		for (String line : task.strip().split("\\R")) {
			sb.append("> ").append(line).append("\n");
		}
		sb.append("\n**Exported:** ").append(exportedAt).append("\n\n");
		sb
			.append("**Files:** ")
			.append(items.size())
			.append(" (about ")
			.append(tokens)
			.append(" tokens, ")
			.append(bytes / 1024)
			.append(" KB)\n\n");
		sb.append("> ").append(FilePackService.DISCLAIMER).append("\n>\n");
		sb.append("> **This archive contains source code.** Share it only with people who are allowed to see it.\n\n");
		sb.append("## What is inside\n\n");
		sb.append(
			"- `files/` - the selected files with their original relative paths, copied byte-for-byte unchanged.\n"
		);
		sb.append(
			"- `manifest.json` - every file with its category, relevance score, reason, size and token estimate.\n"
		);
		sb.append("- `README.md` - this file.\n\n");
		sb.append("## Files by category\n\n");
		for (Category category : Category.values()) {
			List<Item> inCategory = items.stream().filter(i -> i.category() == category).toList();
			if (inCategory.isEmpty()) {
				continue;
			}
			sb.append("### ").append(category.label()).append("\n\n");
			for (Item it : inCategory) {
				sb
					.append("- `")
					.append(it.path())
					.append("` - ")
					.append(it.reason())
					.append(" (score ")
					.append(String.format(Locale.ROOT, "%.2f", it.score()))
					.append(")\n");
			}
			sb.append("\n");
		}
		if (!excluded.isEmpty()) {
			sb.append("## Left out\n\n");
			for (ExcludedFile e : excluded) {
				sb.append("- `").append(e.path()).append("` - ").append(e.reason()).append("\n");
			}
			sb.append("\n");
		}
		sb.append("## Usage notes\n\n");
		sb.append("- Token counts are estimates and differ between AI models.\n");
		sb.append("- Files that looked like secrets or credentials are never included.\n");
		sb.append("- The export is read-only: your original project files were not changed.\n");
		return sb.toString();
	}

	/** Minimal JSON string escaping, so the manifest does not depend on a JSON library. */
	static String json(String s) {
		if (s == null) {
			return "null";
		}
		StringBuilder sb = new StringBuilder("\"");
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		return sb.append('"').toString();
	}
}
