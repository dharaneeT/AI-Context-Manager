package com.contextlayer.backend.symbols;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SymbolExtractorTest {

	@Test
	void java() {
		String code =
			"""
                package demo;

                /** Handles login. Second sentence ignored. */
                @RestController
                @RequestMapping("/api")
                public class AuthController {

                    /** Returns the token. */
                    @GetMapping("/token")
                    public String token(String user) {
                        return "x";
                    }

                    public AuthController() {
                    }

                    static class Inner {
                        void ping() {
                        }
                    }
                }
                """;

		List<ExtractedSymbol> symbols = new JavaSymbolExtractor().extract(code);

		assertThat(symbols)
			.extracting(ExtractedSymbol::qualifiedName)
			.containsExactly(
				"AuthController",
				"AuthController.token",
				"AuthController.AuthController",
				"AuthController.Inner",
				"AuthController.Inner.ping"
			);
		ExtractedSymbol type = symbols.get(0);
		assertThat(type.kind()).isEqualTo(SymbolKind.CLASS);
		assertThat(type.startLine()).isEqualTo(3); // starts at the Javadoc
		assertThat(type.doc()).isEqualTo("Handles login.");
		assertThat(type.annotations()).contains("@RestController").contains("/api");
		ExtractedSymbol method = symbols.get(1);
		assertThat(method.kind()).isEqualTo(SymbolKind.METHOD);
		assertThat(method.signature()).contains("String token(String user)");
		assertThat(method.annotations()).contains("@GetMapping");
		assertThat(method.endLine()).isGreaterThan(method.startLine());
	}

	@Test
	void script() {
		String code =
			"""
                /** Formats a number. */
                export function formatNumber(n) {
                  return String(n);
                }

                export const App = () => {
                  return 1;
                };

                export class Store {
                  load(id) {
                    if (id) {
                      return 1;
                    }
                  }
                }

                export interface Props { a: string }
                """;

		List<ExtractedSymbol> symbols = new ScriptSymbolExtractor().extract(code);

		assertThat(symbols)
			.extracting(ExtractedSymbol::qualifiedName)
			.containsExactly("formatNumber", "App", "Store", "Store.load", "Props");
		assertThat(symbols.get(0).doc()).isEqualTo("Formats a number.");
		assertThat(symbols.get(0).endLine()).isEqualTo(4);
		assertThat(symbols.get(3).kind()).isEqualTo(SymbolKind.METHOD);
	}

	@Test
	void python() {
		String code =
			"""
                class Repo:
                    \"\"\"Stores things. More.\"\"\"

                    def get(self, id):
                        return id

                @cache
                def helper():
                    pass
                """;

		List<ExtractedSymbol> symbols = new PythonSymbolExtractor().extract(code);

		assertThat(symbols).extracting(ExtractedSymbol::qualifiedName).containsExactly("Repo", "Repo.get", "helper");
		assertThat(symbols.get(0).doc()).isEqualTo("Stores things.");
		assertThat(symbols.get(1).kind()).isEqualTo(SymbolKind.METHOD);
		assertThat(symbols.get(2).kind()).isEqualTo(SymbolKind.FUNCTION);
		assertThat(symbols.get(2).startLine()).isEqualTo(7); // includes the decorator
	}
}
