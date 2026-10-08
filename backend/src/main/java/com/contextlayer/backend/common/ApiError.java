package com.contextlayer.backend.common;

import java.util.Map;

/** The one JSON shape every error response uses. */
public record ApiError(
	int status,
	String error,
	String message,
	String path,
	String timestamp,
	Map<String, String> fieldErrors
) {}
