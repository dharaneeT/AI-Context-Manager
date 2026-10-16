package com.contextlayer.backend.project;

import com.contextlayer.backend.common.BadRequestException;
import com.contextlayer.backend.common.ConflictException;
import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.config.ProjectsProperties;
import com.contextlayer.backend.project.dto.CreateProjectRequest;
import com.contextlayer.backend.project.dto.ProjectResponse;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectService {

	private final ProjectRepository projectRepository;
	private final ProjectsProperties projectsProperties;

	@Transactional
	public ProjectResponse create(CreateProjectRequest request) {
		String normalizedRoot = resolveRoot(request.rootPath()).toString();

		if (projectRepository.existsByRootPath(normalizedRoot)) {
			throw new ConflictException("A project for this folder already exists: " + normalizedRoot);
		}

		Project saved = projectRepository.save(
			Project
				.builder()
				.name(request.name().strip())
				.rootPath(normalizedRoot)
				.description(request.description())
				.build()
		);

		return ProjectResponse.from(saved);
	}

	@Transactional(readOnly = true)
	public List<ProjectResponse> list() {
		return projectRepository
			.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
			.stream()
			.map(ProjectResponse::from)
			.toList();
	}

	@Transactional(readOnly = true)
	public ProjectResponse get(Long id) {
		return ProjectResponse.from(getEntity(id));
	}

	/** For other services that need the entity itself. */
	@Transactional(readOnly = true)
	public Project getEntity(Long id) {
		return projectRepository.findById(id).orElseThrow(() -> new NotFoundException("Project " + id + " not found"));
	}

	@Transactional
	public void delete(Long id) {
		if (!projectRepository.existsById(id)) {
			throw new NotFoundException("Project " + id + " not found");
		}

		projectRepository.deleteById(id);
		// Files and chunks vanish via ON DELETE CASCADE.
	}

	private Path resolveRoot(String raw) {
		Path path; //An object that may be used to locate a file in a file system

		try {
			path = Path.of(raw.strip()).toAbsolutePath().normalize();
		} catch (InvalidPathException e) {
			throw new BadRequestException("Not a valid path: " + raw);
		}

		if (!isUnderAllowedRoots(path, projectsProperties.allowedRoots())) {
			throw new BadRequestException("Folder is outside the allowed project roots: " + path);
		}

		if (!Files.isDirectory(path)) {
			throw new BadRequestException("Folder does not exist or is not a directory: " + path);
		}

		return path;
	}

	/**
	 * An empty allowed-roots list allows any folder.
	 * Path comparisons avoid incorrect string-prefix matches, such as /work2 being
	 * considered inside /work.
	 */
	static boolean isUnderAllowedRoots(Path path, List<String> allowedRoots) {
		if (allowedRoots == null || allowedRoots.isEmpty()) {
			return true;
		}

		return allowedRoots.stream().map(root -> Path.of(root).toAbsolutePath().normalize()).anyMatch(path::startsWith);
	}
}
