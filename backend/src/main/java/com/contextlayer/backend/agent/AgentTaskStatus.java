package com.contextlayer.backend.agent;

/** Days 1-5 end at CONTEXT_READY. Later days add PLANNED, IMPLEMENTING, VERIFYING, COMPLETE and so on. */
public enum AgentTaskStatus {
	QUEUED,
	RUNNING,
	CONTEXT_READY,
	FAILED,
	CANCELLED;

	public boolean isActive() {
		return this == QUEUED || this == RUNNING;
	}
}
