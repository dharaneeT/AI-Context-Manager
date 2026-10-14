package com.contextlayer.backend.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AllowedRootsTest {

	@TempDir
	Path base;

	@Test
	void noRestrictionsAllowsAnything() {
		assertThat(ProjectService.isUnderAllowedRoots(base, List.of())).isTrue();
	}

	@Test
	void onlyFoldersInsideAnAllowedRootPass() {
		Path allowed = base.resolve("work");
		List<String> roots = List.of(allowed.toString());

		assertThat(ProjectService.isUnderAllowedRoots(allowed.resolve("repo"), roots)).isTrue();
		assertThat(ProjectService.isUnderAllowedRoots(base.resolve("work2/repo"), roots)).isFalse();
		assertThat(ProjectService.isUnderAllowedRoots(allowed.resolve("../secret").normalize(), roots)).isFalse();
	}
}
