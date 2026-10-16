package com.contextlayer.backend.filepack;

import com.contextlayer.backend.common.BadRequestException;
import com.contextlayer.backend.common.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;

/**
 * The only place in the feature that touches the disk. It is read-only and very strict:
 * every path is checked again right before the file is opened, and error messages never contain
 * absolute paths from your computer.
 */
public final class SafeProjectFiles {

	private static final int MAX_PATH_LENGTH = 1000;
	private static final int BINARY_SNIFF_BYTES = 8000;

	/** A file anywhere below one of these folders is never exported. */
	private static final Set<String> BLOCKED_DIRECTORIES = Set.of(
		".git",
		".svn",
		".hg",
		"node_modules",
		"target",
		"build",
		"dist",
		"out",
		".idea",
		".vscode",
		".gradle",
		"__pycache__",
		".venv",
		"venv",
		"coverage",
		".next",
		"pgdata",
		"postgres-data",
		"pg_data",
		".ssh",
		".aws",
		".gnupg",
		".docker",
		"secrets",
		".secrets"
	);

	private static final Set<String> BLOCKED_NAMES = Set.of(
		".envrc",
		".npmrc",
		".pypirc",
		".netrc",
		".htpasswd",
		".git-credentials",
		"credentials",
		"secrets.json",
		"secrets.yml",
		"secrets.yaml",
		"id_rsa",
		"id_dsa",
		"id_ecdsa",
		"id_ed25519"
	);

	private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
		"pem",
		"key",
		"p12",
		"pfx",
		"jks",
		"keystore",
		"kdbx",
		"tfstate",
		"ppk",
		"gpg"
	);

	private SafeProjectFiles() {}

	/** Checks that a client-supplied path is a clean relative path using '/'. Returns it unchanged. */
	public static String requireRelative(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new BadRequestException("A file path is empty.");
		}
		if (raw.length() > MAX_PATH_LENGTH) {
			throw new BadRequestException("A file path is too long.");
		}
		if (raw.startsWith("/")) {
			throw new BadRequestException("Absolute paths are not allowed: " + raw);
		}
		// Backslash, colon (C:\ drives, Windows alternate data streams) and control characters.
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '\\' || c == ':' || c < 0x20) {
				throw new BadRequestException("Only relative paths written with '/' are allowed.");
			}
		}
		for (String segment : raw.split("/", -1)) {
			if (segment.isEmpty()) {
				throw new BadRequestException("Invalid file path: " + raw);
			}
			if (segment.chars().allMatch(c -> c == '.')) {
				throw new BadRequestException("Path traversal ('.' or '..') is not allowed: " + raw);
			}
		}
		return raw;
	}

	/** @return why this path must never be exported, or null when it is allowed. */
	public static String blockedReason(String relative) {
		String[] parts = relative.toLowerCase(Locale.ROOT).split("/");
		for (int i = 0; i < parts.length - 1; i++) {
			if (BLOCKED_DIRECTORIES.contains(parts[i])) {
				return "it is inside an excluded folder (" + parts[i] + ")";
			}
		}
		String name = parts[parts.length - 1];
		int dot = name.lastIndexOf('.');
		String ext = dot <= 0 ? "" : name.substring(dot + 1);
		if (
			name.startsWith(".env") ||
			name.endsWith(".env") ||
			BLOCKED_NAMES.contains(name) ||
			name.startsWith("id_rsa") ||
			name.startsWith("id_ed25519") ||
			name.startsWith("credentials") ||
			name.startsWith("secrets.") ||
			(name.startsWith("service-account") && name.endsWith(".json")) ||
			BLOCKED_EXTENSIONS.contains(ext)
		) {
			return "it looks like a secret or credentials file";
		}
		return null;
	}

	/** The project folder with all links resolved. Used as the fence every file must stay inside. */
	public static Path realRoot(String rootPath) {
		try {
			return Path.of(rootPath).toRealPath();
		} catch (IOException | InvalidPathException e) {
			throw new BadRequestException("The project folder is not available.");
		}
	}

	/**
	 * Validates the path and reads the whole file. Everything is re-checked here (path, blocked names,
	 * symbolic links, size, binary content), even if an earlier step already looked at the same file.
	 */
	public static byte[] read(Path realRoot, String relative, long maxFileSizeBytes) {
		requireRelative(relative);
		String reason = blockedReason(relative);
		if (reason != null) {
			throw new BadRequestException("This file cannot be exported because " + reason + ": " + relative);
		}
		Path file = resolveRegularFile(realRoot, relative, maxFileSizeBytes);
		try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
			// Read one byte more than allowed: if the file grew after the check, we still notice.
			byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, maxFileSizeBytes + 1));
			if (bytes.length > maxFileSizeBytes) {
				throw tooLarge(relative, maxFileSizeBytes);
			}
			if (looksBinary(bytes)) {
				throw new BadRequestException("Binary files are not supported: " + relative);
			}
			return bytes;
		} catch (IOException e) {
			throw new BadRequestException("The file could not be read: " + relative);
		}
	}

	private static Path resolveRegularFile(Path realRoot, String relative, long maxFileSizeBytes) {
		Path current = realRoot;
		for (String segment : relative.split("/")) {
			current = current.resolve(segment);
			if (Files.isSymbolicLink(current)) {
				throw new BadRequestException("Symbolic links are not exported: " + relative);
			}
		}
		if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
			throw new NotFoundException("File no longer exists (refresh the index): " + relative);
		}
		try {
			Path real = current.toRealPath();
			if (!real.startsWith(realRoot)) {
				throw new BadRequestException("The path points outside the project folder: " + relative);
			}
			String realRelative = realRoot.relativize(real).toString().replace('\\', '/');
			String reason = blockedReason(realRelative);
			if (reason != null) {
				throw new BadRequestException("This file cannot be exported because " + reason + ": " + relative);
			}
			BasicFileAttributes attrs = Files.readAttributes(
				real,
				BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS
			);
			if (attrs.isDirectory()) {
				throw new BadRequestException("Directories cannot be exported: " + relative);
			}
			if (!attrs.isRegularFile()) {
				throw new BadRequestException("Only regular files can be exported: " + relative);
			}
			if (attrs.size() > maxFileSizeBytes) {
				throw tooLarge(relative, maxFileSizeBytes);
			}
			return real;
		} catch (IOException e) {
			throw new BadRequestException("The file could not be read: " + relative);
		}
	}

	private static BadRequestException tooLarge(String relative, long max) {
		return new BadRequestException("File is too large to export (limit " + (max / 1024) + " KB): " + relative);
	}

	private static boolean looksBinary(byte[] bytes) {
		int n = Math.min(bytes.length, BINARY_SNIFF_BYTES);
		for (int i = 0; i < n; i++) {
			if (bytes[i] == 0) {
				return true;
			}
		}
		return false;
	}
}
