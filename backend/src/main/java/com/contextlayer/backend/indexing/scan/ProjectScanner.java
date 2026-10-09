package com.contextlayer.backend.indexing.scan;

import com.contextlayer.backend.config.IndexingProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.contextlayer.backend.indexing.IgnoreRules;
import com.contextlayer.backend.indexing.LanguageDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectScanner {

	private final IndexingProperties props; //provides rules for ignoring files and directories.
	private final LanguageDetector languageDetector; //identifies the likely programming language of each file.

	public ScanResult scan(Path root) {
		IgnoreRules rules = IgnoreRules.fromGitignore(root);
		List<ScannedFile> files = new ArrayList<>(); //will hold metadata for accepted files.
		Map<String, Integer> skipped = new TreeMap<>(); //counts why other files or directories were excluded.

		try {
			Files.walkFileTree( //recursively visits the folder and its descendants.
				root,
					new SimpleFileVisitor<>() { //It also checks the .gitignore rules for the directory.
												//  If the directory is ignored, its subtree is skipped as well
					@Override
					public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
						if (dir.equals(root)) {
							return FileVisitResult.CONTINUE;
						}
						String name = dir.getFileName().toString();
						if (props.ignoredDirectories().contains(name)) {
							skipped.merge("ignored-directory", 1, Integer::sum);
							return FileVisitResult.SKIP_SUBTREE; // never descend into node_modules
						}
							if (rules.isIgnored(root.relativize(dir), true)) {
								skipped.merge("gitignore", 1, Integer::sum);
								return FileVisitResult.SKIP_SUBTREE;
							}
						
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
						String reason = rejectionReason(root, file, attrs, rules);
						if (reason != null) {
							skipped.merge(reason, 1, Integer::sum);
							return FileVisitResult.CONTINUE;
						}
						String name = file.getFileName().toString();
						String ext = extensionOf(name);
						files.add(
							new ScannedFile(
								root.relativize(file).toString().replace('\\', '/'),
								file,
								ext,
								languageDetector.detect(name, ext),
								attrs.size(),
								// Truncated to millis: Postgres stores microseconds, and an exact
								// nanosecond comparison on Day 5 would never match.
								attrs.lastModifiedTime().toInstant().truncatedTo(ChronoUnit.MILLIS)
							)
						);
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFileFailed(Path file, IOException exc) {
						log.warn("Cannot read {}: {}", file, exc.getMessage());
						skipped.merge("unreadable", 1, Integer::sum);
						return FileVisitResult.CONTINUE;
					}
				}
			);
		} catch (IOException e) {
			throw new UncheckedIOException("Scan failed for " + root, e);
		}

		files.sort((a, b) -> a.relativePath().compareTo(b.relativePath()));
		return new ScanResult(files, skipped);
	}

	/** @return why the file must be skipped, or null if it should be indexed. */
	private String rejectionReason(Path root, Path file, BasicFileAttributes attrs, IgnoreRules rules) {
		if (!attrs.isRegularFile()) {
			return "not-regular-file"; // symlinks, sockets, etc.
		}
		String name = file.getFileName().toString();
		if (props.ignoredFileNames().contains(name)) {
			return "ignored-file-name";
		}
		if (props.ignoredExtensions().contains(extensionOf(name))) {
			return "ignored-extension";
		}
		if (rules.isIgnored(root.relativize(file), false)) {
			return "gitignore";
		}
		if (attrs.size() == 0) {
			return "empty";
		}
		if (attrs.size() > props.maxFileSizeBytes()) {
			return "too-large";
		}
		try {
			if (looksBinary(file)) {
				return "binary-content";
			}
		} catch (IOException e) {
			return "unreadable";
		}
		return null;

		
		//If the method returns a reason, the file is skipped and the counter is incremented:
		//If it returns null, the file is eligible for indexing.
	}

	/** A NUL byte in the first 8 KB is the classic sign of a binary file (git uses the same trick). */
	//This function tries to identify binary content.
	private boolean looksBinary(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			for (byte b : in.readNBytes(8000)) {
				if (b == 0) {
					return true;
				}
			}
		}
		return false;
	}

	static String extensionOf(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot <= 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
	}
}
