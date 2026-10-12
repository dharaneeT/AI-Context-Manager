package com.contextlayer.backend.compress;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextlayer.backend.symbols.CodeSymbol;
import com.contextlayer.backend.symbols.SymbolKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompressionTest {

	private static final String SOURCE =
		"""
            package demo;
            import a.B;
            import c.D;

            public class AuthService {
                int sessionTimeoutSeconds = 30;

                void refreshToken() {
                    doRefresh();
                }

                void unrelated() {
                    x();
                    y();
                }
            }
            """;

	private static CodeSymbol symbol(SymbolKind kind, String name, int start, int end) {
		CodeSymbol s = new CodeSymbol();
		s.setKind(kind);
		s.setName(name);
		s.setQualifiedName(name);
		s.setStartLine(start);
		s.setEndLine(end);
		return s;
	}

	private final List<CodeSymbol> symbols = List.of(
		symbol(SymbolKind.CLASS, "AuthService", 5, 16),
		symbol(SymbolKind.METHOD, "refreshToken", 8, 10),
		symbol(SymbolKind.METHOD, "unrelated", 12, 15)
	);

	@Test
	void minifierCollapsesBlankLinesAndStripsLicenseHeaders() {
		String code = "/*\n * Copyright 2020 ACME\n */\n\n\npackage a;   \n\n\n\nclass A {}\n";

		assertThat(CodeMinifier.minify(code)).isEqualTo("package a;\n\nclass A {}");
	}

	@Test
	void minifierKeepsAJavadocThatMerelyMentionsLicenses() {
		String code = "/** Licensing helper. */\nclass A {}";

		assertThat(CodeMinifier.minify(code)).contains("Licensing helper");
	}

	@Test
	void minifierPreservesTheFirstLinesIndentation() {
		assertThat(CodeMinifier.minify("    int x;\n    int y;")).startsWith("    int x;");
	}

	@Test
	void focusKeepsMatchingFieldAndFoldsOtherMethods() {
		String view = FocusedView.build(SOURCE, symbols, List.of("timeout"));

		assertThat(view)
			.contains("sessionTimeoutSeconds = 30") // the match, outside any method
			.contains("void refreshToken()")
			.contains("void unrelated()") // signatures survive
			.doesNotContain("doRefresh();")
			.doesNotContain("x();") // bodies are folded
			.contains("omitted");
	}

	@Test
	void aRelevantMethodIsKeptInFull() {
		String view = FocusedView.build(SOURCE, symbols, List.of("dorefresh"));

		assertThat(view).contains("doRefresh();").doesNotContain("x();");
	}

	@Test
	void noKeywordHitMeansNoFocusedView() {
		assertThat(FocusedView.build(SOURCE, symbols, List.of("nonexistent"))).isNull();
	}
}
