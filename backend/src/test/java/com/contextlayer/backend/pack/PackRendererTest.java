package com.contextlayer.backend.pack;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextlayer.backend.pack.PackModel.Role;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackRendererTest {

	private final List<PackRenderer.Item> items = List.of(
		new PackRenderer.Item(
			"a/Auth.java",
			Role.PRIMARY,
			"CODE_1",
			0.9,
			List.of("keywords found in its code (#1)"),
			"#### a/Auth.java (lines 1-5)\n```java\nclass Auth {}\n```"
		),
		new PackRenderer.Item(
			"a/Token.java",
			Role.RELATED,
			"SUMMARY",
			0.4,
			List.of("imports/uses top match a/Auth.java"),
			"**a/Token.java** — Stores tokens."
		),
		new PackRenderer.Item(
			"a/Util.java",
			Role.ALSO_SEE,
			"PATH_ONLY",
			0.1,
			List.of("path contains 'auth'"),
			"- a/Util.java"
		)
	);

	private PackRenderer.Input input(boolean explain) {
		return new PackRenderer.Input(
			"fix auth",
			"Project \"x\": 3 files.",
			items,
			List.of("a/Other.java"),
			List.of("a/Auth.java — last changed 2 day(s) ago by Ann: \"fix\""),
			explain
		);
	}

	@Test
	void sectionsAppearInOrderOfImportance() {
		String md = PackRenderer.render(input(true));

		assertThat(md.indexOf("## Project")).isLessThan(md.indexOf("## Relevant code"));
		assertThat(md.indexOf("## Relevant code")).isLessThan(md.indexOf("## Related files"));
		assertThat(md.indexOf("## Related files")).isLessThan(md.indexOf("## Also see"));
		assertThat(md.indexOf("## Also see")).isLessThan(md.indexOf("## Recent activity"));
	}

	@Test
	void explainAddsReasonsAndOmissions() {
		String md = PackRenderer.render(input(true));

		assertThat(md)
			.contains("*Why selected: keywords found in its code (#1)*")
			.contains("- a/Util.java — path contains 'auth'")
			.contains("Considered but omitted for space")
			.contains("a/Other.java");
	}

	@Test
	void withoutExplainTheReasonsDisappear() {
		String md = PackRenderer.render(input(false));

		assertThat(md)
			.doesNotContain("Why selected")
			.doesNotContain("omitted for space")
			.contains("- a/Util.java")
			.contains("class Auth {}");
	}
}
