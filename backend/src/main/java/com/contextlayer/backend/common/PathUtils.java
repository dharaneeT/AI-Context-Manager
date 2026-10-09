package com.contextlayer.backend.common;

import java.util.ArrayDeque;
import java.util.Deque;

/** Helpers for '/'-separated project-relative paths (we never use OS-specific separators in the DB). */
public final class PathUtils {

	private PathUtils() {}

	/** "src/a/B.java" -> "src/a"; "B.java" -> "". */
	public static String parentDir(String path) {
		int i = path.lastIndexOf('/');
		return i < 0 ? "" : path.substring(0, i);
	}

	/** Resolves "." and ".." segments. A ".." above the root is ignored instead of failing. */
	public static String normalize(String path) {
		Deque<String> out = new ArrayDeque<>();
		for (String part : path.split("/")) {
			if (part.isEmpty() || part.equals(".")) {
				continue;
			}
			if (part.equals("..")) {
				if (!out.isEmpty()) {
					out.removeLast();
				}
			} else {
				out.addLast(part);
			}
		}
		return String.join("/", out);
	}

	public static String join(String dir, String relative) {
		return normalize(dir.isEmpty() ? relative : dir + "/" + relative);
	}

	/** "src/a/Foo.java" -> "Foo". */
	public static String stem(String path) {
		String name = path.substring(path.lastIndexOf('/') + 1);
		int dot = name.lastIndexOf('.');
		return dot <= 0 ? name : name.substring(0, dot);
	}
}
