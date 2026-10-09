package com.contextlayer.backend.git;

import java.time.Instant;

public final class GitResponses {

	private GitResponses() {}

	public record SyncResult(int commitsAnalyzed, int filesTracked, int coChangePairs) {}

	public record HotFile(
		String path,
		int commits,
		int commitsLast30Days,
		int commitsLast90Days,
		int authors,
		Instant lastCommitAt,
		String lastAuthor,
		String lastSubject
	) {}

	public record RecentCommit(String hash, String author, Instant committedAt, String subject, int filesChanged) {}

	public record CoChange(String path, int count) {}
}
