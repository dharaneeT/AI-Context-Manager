package com.contextlayer.backend.indexing;

import com.contextlayer.backend.indexing.scan.ProjectScanner;
import com.contextlayer.backend.indexing.scan.ScanPreview;
import com.contextlayer.backend.indexing.scan.ScanResult;
import com.contextlayer.backend.indexing.scan.ScannedFile;
import com.contextlayer.backend.project.Project;
import com.contextlayer.backend.project.ProjectService;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class IndexingController {

	private final ProjectService projectService;
	private final ProjectScanner scanner;
	private final IndexingService indexingService;

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

	//	@PostMapping("/index")
	//	public IndexResult index(@PathVariable Long projectId) {
	//		return indexingService.indexProject(projectId);
	//	}

	@GetMapping("/stats")
	public ProjectStats stats(@PathVariable Long projectId) {
		return indexingService.stats(projectId);
	}

	@PostMapping("/index")
	public IndexResult index(@PathVariable Long projectId, @RequestParam(defaultValue = "false") boolean force) {
		return indexingService.indexProject(projectId, force);
	}
}
