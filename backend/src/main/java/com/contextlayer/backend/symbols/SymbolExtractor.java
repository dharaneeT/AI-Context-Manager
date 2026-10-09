package com.contextlayer.backend.symbols;

import java.util.List;
import java.util.Set;

public interface SymbolExtractor {
	Set<String> languages();

	List<ExtractedSymbol> extract(String content);
}
