package com.contextlayer.backend.summary;

import com.contextlayer.backend.symbols.CodeSymbol;
import java.util.List;

public record SummaryInput(String path, String language, int lineCount, List<CodeSymbol> symbols, String content) {}
