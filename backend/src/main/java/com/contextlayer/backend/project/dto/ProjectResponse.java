package com.contextlayer.backend.project.dto;

import com.contextlayer.backend.project.IndexStatus;
import com.contextlayer.backend.project.Project;
import java.time.Instant;

public record ProjectResponse(
	Long id,
	String name,
	String rootPath,
	String description,
	IndexStatus indexStatus,
	Instant lastIndexedAt,
	Instant createdAt
) {
	public static ProjectResponse from(Project p) {
		return new ProjectResponse(
			p.getId(),
			p.getName(),
			p.getRootPath(),
			p.getDescription(),
			p.getIndexStatus(),
			p.getLastIndexedAt(),
			p.getCreatedAt()
		);
	}
}
