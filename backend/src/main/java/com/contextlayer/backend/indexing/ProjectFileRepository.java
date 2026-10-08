package com.contextlayer.backend.indexing;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectFileRepository extends JpaRepository<ProjectFile, Long> {
	List<ProjectFile> findAllByProjectId(Long projectId);
	long countByProjectId(Long projectId);
}
