package com.contextlayer.backend.compress;

import com.contextlayer.backend.budget.TokenCounter;
import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.indexing.ProjectFileRepository;
import com.contextlayer.backend.project.ProjectService;
import com.contextlayer.backend.search.KeywordExtractor;
import com.contextlayer.backend.symbols.CodeSymbolRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class CompressionController {

	public record Preview(
		String path,
		int fullTokens,
		int minifiedTokens,
		int focusedTokens,
		double focusedSavedPercent,
		String focused
	) {}

	private final ProjectService projectService;
	private final ProjectFileRepository fileRepository;
	private final CodeSymbolRepository symbolRepository;
	private final KeywordExtractor extractor;
	private final TokenCounter tokens;

	@GetMapping("/compress-preview")
	public Preview preview(@PathVariable Long projectId, @RequestParam String path, @RequestParam String q) {
		if (!fileRepository.findAllPaths(projectId).contains(path)) { // only indexed files can be read
			throw new NotFoundException("File not indexed in project " + projectId + ": " + path);
		}
		Path root = Path.of(projectService.getEntity(projectId).getRootPath());
		String content;
		try {
			content = new String(Files.readAllBytes(root.resolve(path)), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		String focused = FocusedView.build(
			content,
			symbolRepository.findByPath(projectId, path),
			extractor.extract(q, 12)
		);

		int full = tokens.count(content);
		int minified = tokens.count(CodeMinifier.minify(content));
		int focusedTokens = focused == null ? 0 : tokens.count(CodeMinifier.minify(focused));
		double saved = focused == null || full == 0
			? 0
			: Math.round(1000.0 * (1.0 - (double) focusedTokens / full)) / 10.0;
		return new Preview(path, full, minified, focusedTokens, saved, focused);
	}
}
