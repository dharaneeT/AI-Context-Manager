package com.contextlayer.backend.project;

import com.contextlayer.backend.common.BadRequestException;
import com.contextlayer.backend.common.ConflictException;
import com.contextlayer.backend.common.NotFoundException;
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

	@Transactional
	public ProjectResponse create(CreateProjectRequest request) {
		//These paths may point to the same location:

		//Normalization removes redundant path elements, 
		// and converting to an absolute path gives a consistent representation.
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
		projectRepository.deleteById(id); // files and chunks vanish via ON DELETE CASCADE
	}

	private static Path resolveRoot(String raw) {
		Path path;
		try {
			path = Path.of(raw.strip()).toAbsolutePath().normalize();
		} catch (InvalidPathException e) {
			throw new BadRequestException("Not a valid path: " + raw);
		}
		if (!Files.isDirectory(path)) {
			throw new BadRequestException("Folder does not exist or is not a directory: " + path);
		}
		return path;
	}
}
