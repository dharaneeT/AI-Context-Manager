package com.contextlayer.backend.budget;

public final class CodeBlocks {

	private CodeBlocks() {}

	public static String fence(String heading, String language, String code) {
		return (
			"#### " +
			heading +
			"\n```" +
			language +
			"\n" +
			com.contextlayer.backend.compress.CodeMinifier.minify(code) +
			"\n```"
		);
	}

	/** The file extension doubles as the markdown fence language tag. */
	public static String languageTag(String path) {
		int dot = path.lastIndexOf('.');
		return dot < 0 || dot < path.lastIndexOf('/') ? "" : path.substring(dot + 1);
	}
}
