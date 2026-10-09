package com.contextlayer.backend.common;

public final class Text {

	private Text() {}

	public static String truncate(String s, int max) {
		if (s == null) {
			return null;
		}
		return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
	}

	/** Collapses whitespace and returns the first sentence (max 240 chars), or null if blank. */
	public static String firstSentence(String s) {
		if (s == null) {
			return null;
		}
		String flat = s.replaceAll("\\s+", " ").strip();
		if (flat.isEmpty()) {
			return null;
		}
		int dot = flat.indexOf(". ");
		return truncate(dot > 0 ? flat.substring(0, dot + 1) : flat, 240);
	}
}
