package com.contextlayer.backend.symbols;

import com.contextlayer.backend.common.BadRequestException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/symbols")
@RequiredArgsConstructor
public class SymbolController {

	public record SymbolResponse(
		String kind,
		String qualifiedName,
		String signature,
		String path,
		int startLine,
		int endLine,
		String doc,
		String annotations
	) {
		static SymbolResponse from(CodeSymbol s) {
			return new SymbolResponse(
				s.getKind().name(),
				s.getQualifiedName(),
				s.getSignature(),
				s.getFile().getRelativePath(),
				s.getStartLine(),
				s.getEndLine(),
				s.getDoc(),
				s.getAnnotations()
			);
		}
	}

	private final CodeSymbolRepository repository;

	/** Either ?path=file/to/inspect.java (all its symbols) or ?q=name (search by name). */
	@GetMapping
	@Transactional(readOnly = true)
	public List<SymbolResponse> symbols(
		@PathVariable Long projectId,
		@RequestParam(required = false) String path,
		@RequestParam(required = false) String q,
		@RequestParam(defaultValue = "50") int limit
	) {
		if (path != null && !path.isBlank()) {
			return repository.findByPath(projectId, path).stream().map(SymbolResponse::from).toList();
		}
		if (q != null && !q.isBlank()) {
			return repository
				.searchByName(projectId, q.toLowerCase(), PageRequest.of(0, Math.min(limit, 200)))
				.stream()
				.map(SymbolResponse::from)
				.toList();
		}
		throw new BadRequestException("Provide either 'path' or 'q'");
	}
}
