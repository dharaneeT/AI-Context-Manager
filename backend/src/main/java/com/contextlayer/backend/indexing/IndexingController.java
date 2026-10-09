package com.contextlayer.backend.indexing;

import com.contextlayer.backend.project.Project;
import com.contextlayer.backend.project.ProjectService;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class IndexingController {

	private final ProjectService projectService;
	private final ProjectScanner scanner;

	/** Dry run: shows what would be indexed. Writes nothing. */
	@GetMapping("/scan-preview")
	public ScanPreview scanPreview(@PathVariable Long projectId) {
		Project project = projectService.getEntity(projectId);
		ScanResult result = scanner.scan(Path.of(project.getRootPath()));

		Map<String, Long> byLanguage = result
			.files()
			.stream()
			.collect(Collectors.groupingBy(ScannedFile::language, TreeMap::new, Collectors.counting()));

		return new ScanPreview(
			result.files().size(),
			result.files().stream().mapToLong(ScannedFile::sizeBytes).sum(),
			byLanguage,
			result.skipped(),
			result.files().stream().limit(50).map(ScannedFile::relativePath).toList()
		);
	}
}
