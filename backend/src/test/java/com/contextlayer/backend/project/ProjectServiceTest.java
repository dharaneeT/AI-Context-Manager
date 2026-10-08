package com.contextlayer.backend.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.contextlayer.backend.common.BadRequestException;
import com.contextlayer.backend.common.ConflictException;
import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.project.dto.CreateProjectRequest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional // each test rolls back, so tests don't affect each other
class ProjectServiceTest {

	@Autowired
	ProjectService service;

	@TempDir
	Path folder;

	@Test
	void createsProjectForExistingFolder() {
		var created = service.create(new CreateProjectRequest("demo", folder.toString(), "d"));

		assertThat(created.id()).isNotNull();
		assertThat(created.indexStatus()).isEqualTo(IndexStatus.NEVER_INDEXED);
		assertThat(service.list()).hasSize(1);
	}

	@Test
	void rejectsMissingFolder() {
		String missing = folder.resolve("nope").toString();
		assertThatThrownBy(() -> service.create(new CreateProjectRequest("x", missing, null)))
			.isInstanceOf(BadRequestException.class);
	}

	@Test
	void rejectsSameFolderTwice()   {
		service.create(new CreateProjectRequest("a", folder.toString(), null));
		assertThatThrownBy(() -> service.create(new CreateProjectRequest("b", folder.toString() + "/", null)))
			.isInstanceOf(ConflictException.class);
	}

	@Test
	void deleteOfUnknownIdIsNotFound() {
		assertThatThrownBy(() -> service.delete(999_999L)).isInstanceOf(NotFoundException.class);
	}
}
