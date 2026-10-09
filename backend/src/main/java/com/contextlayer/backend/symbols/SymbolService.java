package com.contextlayer.backend.symbols;

import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.indexing.ProjectFile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class SymbolService {

	private final Map<String, SymbolExtractor> extractors = new HashMap<>();
	private final CodeSymbolRepository repository;

	public SymbolService(List<SymbolExtractor> all, CodeSymbolRepository repository) {
		this.repository = repository;
		for (SymbolExtractor extractor : all) {
			for (String language : extractor.languages()) {
				extractors.put(language, extractor);
			}
		}
	}

	/** Never throws: a file we can't parse simply has no symbols. */
	public List<ExtractedSymbol> extract(String language, String content) {
		SymbolExtractor extractor = extractors.get(language);
		if (extractor == null) {
			return List.of();
		}
		try {
			return extractor.extract(content);
		} catch (RuntimeException e) {
			log.warn("Symbol extraction failed for a {} file: {}", language, e.getMessage());
			return List.of();
		}
	}

	@Transactional
	public void deleteForFile(Long fileId) {
		repository.deleteByFileId(fileId);
	}

	@Transactional
	public void save(ProjectFile file, List<ExtractedSymbol> symbols) {
		repository.saveAll(
			symbols
				.stream()
				.map(s -> {
					CodeSymbol row = new CodeSymbol();
					row.setFile(file);
					row.setKind(s.kind());
					row.setName(Text.truncate(s.name(), 300));
					row.setQualifiedName(Text.truncate(s.qualifiedName(), 500));
					row.setSignature(Text.truncate(s.signature(), 600));
					row.setStartLine(s.startLine());
					row.setEndLine(s.endLine());
					row.setDoc(Text.truncate(s.doc(), 500));
					row.setAnnotations(Text.truncate(s.annotations(), 400));
					return row;
				})
				.toList()
		);
	}
}
