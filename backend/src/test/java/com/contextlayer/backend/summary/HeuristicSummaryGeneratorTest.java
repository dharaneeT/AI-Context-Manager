package com.contextlayer.backend.summary;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextlayer.backend.symbols.CodeSymbol;
import com.contextlayer.backend.symbols.SymbolKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeuristicSummaryGeneratorTest {

	private final HeuristicSummaryGenerator generator = new HeuristicSummaryGenerator();

	private static CodeSymbol symbol(SymbolKind kind, String name, String qualified, String doc, String annotations) {
		CodeSymbol s = new CodeSymbol();
		s.setKind(kind);
		s.setName(name);
		s.setQualifiedName(qualified);
		s.setDoc(doc);
		s.setAnnotations(annotations);
		return s;
	}

	@Test
	void describesControllerWithEndpoints() {
		List<CodeSymbol> symbols = List.of(
			symbol(
				SymbolKind.CLASS,
				"AuthController",
				"AuthController",
				"Handles login.",
				"@RestController @RequestMapping(\"/api\")"
			),
			symbol(SymbolKind.METHOD, "login", "AuthController.login", null, "@PostMapping(\"/login\")"),
			symbol(SymbolKind.METHOD, "refresh", "AuthController.refresh", null, "@GetMapping(\"/refresh\")")
		);

		String text = generator.summarize(new SummaryInput("a/AuthController.java", "java", 40, symbols, ""));

		assertThat(text).contains("AuthController.java is a java file of 40 lines");
		assertThat(text).contains("class AuthController").contains("Handles login.");
		assertThat(text).contains("POST /api/login").contains("GET /api/refresh");
		assertThat(text).contains("Members: login, refresh");
	}

	@Test
	void propertiesSummaryNeverLeaksValues() {
		String content = "spring.datasource.password=SuperSecret123\nserver.port=8080\n";

		String text = generator.summarize(
			new SummaryInput("application.properties", "properties", 2, List.of(), content)
		);

		assertThat(text).contains("spring.datasource.password").contains("server.port");
		assertThat(text).doesNotContain("SuperSecret123").doesNotContain("8080");
	}
}
