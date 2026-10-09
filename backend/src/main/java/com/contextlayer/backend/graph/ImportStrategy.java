package com.contextlayer.backend.graph;

import java.util.List;
import java.util.Set;

public interface ImportStrategy {
	/** Language ids as produced by LanguageDetector. */
	Set<String> languages();

	List<ResolvedImport> analyze(String filePath, String content, ProjectIndex index);
}
