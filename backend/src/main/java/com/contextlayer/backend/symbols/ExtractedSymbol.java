package com.contextlayer.backend.symbols;

/** What an extractor returns. Not yet persisted. Line numbers are 1-based and inclusive. */
public record ExtractedSymbol(
	SymbolKind kind,
	String name,
	String qualifiedName, // "AuthService.refreshToken"
	String signature,
	int startLine,
	int endLine,
	String doc, // first sentence of the doc comment, or null
	String annotations
) {} // "@RestController @RequestMapping("/api")", or null
