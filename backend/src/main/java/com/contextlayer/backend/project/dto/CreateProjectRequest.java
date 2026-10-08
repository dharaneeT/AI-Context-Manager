package com.contextlayer.backend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
	@NotBlank @Size(max = 200) String name,
	@NotBlank @Size(max = 1000) String rootPath,
	@Size(max = 2000) String description
) {}
