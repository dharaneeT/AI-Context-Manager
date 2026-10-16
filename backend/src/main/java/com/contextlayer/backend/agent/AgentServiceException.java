package com.contextlayer.backend.agent;

public class AgentServiceException extends RuntimeException {

	private final boolean unavailable;

	public AgentServiceException(String message, boolean unavailable) {
		super(message);
		this.unavailable = unavailable;
	}

	/** True when the Python service cannot be reached at all: a 503 for the caller, not a 502. */
	public boolean isUnavailable() {
		return unavailable;
	}
}
