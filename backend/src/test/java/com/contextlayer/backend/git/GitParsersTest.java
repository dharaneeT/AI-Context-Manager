package com.contextlayer.backend.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GitParsersTest {

	private static final String RS = "\036";
	private static final String US = "\037";

	@Test
	void parsesLogWithNumstat() {
		String log =
			RS +
			"aaa" +
			US +
			"Alice" +
			US +
			"2026-10-01T10:00:00+05:30" +
			US +
			"Fix login\n\n" +
			"10\t2\tsrc/A.java\n-\t-\timg/logo.bin\n" +
			RS +
			"bbb" +
			US +
			"Bob" +
			US +
			"2026-09-30T09:00:00Z" +
			US +
			"Add thing\n\n" +
			"3\t0\tsrc/B.java\n";

		var commits = GitLogParser.parse(log);

		assertThat(commits).hasSize(2);
		assertThat(commits.get(0).hash()).isEqualTo("aaa");
		assertThat(commits.get(0).author()).isEqualTo("Alice");
		assertThat(commits.get(0).subject()).isEqualTo("Fix login");
		assertThat(commits.get(0).files()).hasSize(2);
		assertThat(commits.get(0).files().get(0).added()).isEqualTo(10);
		assertThat(commits.get(0).files().get(1).added()).isZero(); // binary file: "-"
		assertThat(commits.get(1).files()).extracting(GitLogParser.FileChange::path).containsExactly("src/B.java");
	}

	@Test
	void parsesStatusIncludingRenamesAndSpaces() {
		String status = " M src/A.java\0?? my new file.txt\0R  new.txt\0old.txt\0A  added.txt\0";

		List<GitStatusParser.WorkingChange> changes = GitStatusParser.parse(status);

		assertThat(changes)
			.extracting(GitStatusParser.WorkingChange::path)
			.containsExactly("src/A.java", "my new file.txt", "new.txt", "added.txt");
		assertThat(changes.get(0).status()).isEqualTo("M");
		assertThat(changes.get(1).status()).isEqualTo("??");
	}
}
