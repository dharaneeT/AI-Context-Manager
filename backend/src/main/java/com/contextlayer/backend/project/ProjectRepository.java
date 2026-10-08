package com.contextlayer.backend.project;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {
	boolean existsByRootPath(String rootPath);
}
