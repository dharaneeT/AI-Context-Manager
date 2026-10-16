package com.contextlayer.backend.filepack;

import com.contextlayer.backend.filepack.FilePackModel.DiscoverRequest;
import com.contextlayer.backend.filepack.FilePackModel.DiscoverResponse;
import com.contextlayer.backend.filepack.FilePackModel.ExportRequest;
import com.contextlayer.backend.filepack.FilePackModel.ExportResult;
import com.contextlayer.backend.filepack.FilePackModel.FilePreview;
import jakarta.validation.Valid;
import java.io.InputStream;
import java.nio.file.Files;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/projects/{projectId}/file-pack")
@RequiredArgsConstructor
public class FilePackController {

	private final FilePackService service;

	/** Recommended files for a task. */
	@PostMapping("/discover")
	public DiscoverResponse discover(@PathVariable Long projectId, @Valid @RequestBody DiscoverRequest request) {
		return service.discover(projectId, request);
	}

	/** One file's content (secrets masked). Example: GET .../file-pack/preview?path=src/main/App.java */
	@GetMapping("/preview")
	public FilePreview preview(@PathVariable Long projectId, @RequestParam String path) {
		return service.preview(projectId, path);
	}

	/** The ZIP download. The temporary file is deleted as soon as it has been sent. */
	@PostMapping("/export")
	public ResponseEntity<StreamingResponseBody> export(
		@PathVariable Long projectId,
		@Valid @RequestBody ExportRequest request
	) {
		ExportResult result = service.export(projectId, request);
		StreamingResponseBody body = out -> {
			try (InputStream in = Files.newInputStream(result.file())) {
				in.transferTo(out);
			} finally {
				Files.deleteIfExists(result.file());
			}
		};
		return ResponseEntity
			.ok()
			.contentType(MediaType.parseMediaType("application/zip"))
			.contentLength(result.sizeBytes())
			.header(
				HttpHeaders.CONTENT_DISPOSITION,
				ContentDisposition.attachment().filename(result.fileName()).build().toString()
			)
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.header("X-FilePack-Included", String.valueOf(result.included()))
			.header("X-FilePack-Excluded", String.valueOf(result.excluded()))
			.body(body);
	}
}
