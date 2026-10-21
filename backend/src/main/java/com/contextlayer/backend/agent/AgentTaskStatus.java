package com.contextlayer.backend.agent;

/** Mirrors the statuses the Python agent reports. */
public enum AgentTaskStatus {
	QUEUED,
	RUNNING,
	NEEDS_INPUT, // parked: the agent asked clarifying questions
	CONTEXT_READY,
	AWAITING_APPROVAL, // parked: the plan is written and waits for a person
	APPROVED,
	REJECTED,
	FAILED,
	CANCELLED;

	/** A worker is busy with it. */
	public boolean isActive() {
		return this == QUEUED || this == RUNNING;
	}

	/** Nothing more will happen without the user starting something new. */
	public boolean isTerminal() {
		return this == CONTEXT_READY || this == APPROVED || this == REJECTED || this == FAILED || this == CANCELLED;
	}
}
