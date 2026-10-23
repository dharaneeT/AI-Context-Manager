package com.contextlayer.backend.agent;

/** Mirrors the statuses the Python agent reports. */
public enum AgentTaskStatus {
	QUEUED,
	RUNNING,
	NEEDS_INPUT, // parked: the agent asked clarifying questions
	CONTEXT_READY,
	AWAITING_APPROVAL, // parked: the plan is written and waits for a person
	APPROVED, // the plan is approved; implementation has not started (or is about to)
	REJECTED,
	IMPLEMENTED, // the plan was carried out in the workspace (not verified yet)
	VERIFIED, // the build and tests passed
	VERIFY_FAILED, // they did not (the repair loop takes over)
	COMPLETE, // implemented and verified, possibly after repairs
	PARTIAL, // stopped with something left to do: see the error and the diagnostics
	FAILED,
	CANCELLED;

	/** A worker is busy with it. */
	public boolean isActive() {
		return this == QUEUED || this == RUNNING;
	}

	/** Parked or finished: nothing changes until a person acts (or starts the implementation). */
	public boolean isTerminal() {
		return switch (this) {
			case QUEUED, RUNNING, NEEDS_INPUT, AWAITING_APPROVAL, APPROVED -> false;
			default -> true;
		};
	}
}
