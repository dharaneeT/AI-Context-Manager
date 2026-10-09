package com.contextlayer.backend.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.contextlayer.backend.project.ProjectRepository;
import com.contextlayer.backend.project.ProjectService;
import com.contextlayer.backend.project.dto.CreateProjectRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class GitIntelligenceServiceTest {

	@Autowired
	ProjectService projectService;

	@Autowired
	GitIntelligenceService gitService;

	@Autowired
	ProjectRepository projectRepository;

	@TempDir
	Path repo;

	@BeforeEach
	void requireGit() {
		assumeTrue(gitAvailable(), "git is not installed, skipping");
	}

	@AfterEach
	void cleanUp() {
		projectRepository.deleteAll();
	}

	@Test
	void analysesHistoryHotFilesAndCoChange() throws Exception {
		git("init");
		write("A.java", "v1");
		write("B.java", "v1");
		commit("first");
		write("A.java", "v2");
		write("B.java", "v2");
		commit("second");
		write("A.java", "v3");
		commit("third");

		Long id = projectService.create(new CreateProjectRequest("g", repo.toString(), null)).id();
		var result = gitService.sync(id);

		assertThat(result.commitsAnalyzed()).isEqualTo(3);
		assertThat(result.filesTracked()).isEqualTo(2);

		var hot = gitService.hotFiles(id, 10);
		assertThat(hot.get(0).path()).isEqualTo("A.java");
		assertThat(hot.get(0).commits()).isEqualTo(3);
		assertThat(hot.get(0).commitsLast30Days()).isEqualTo(3);

		var co = gitService.coChanges(id, "A.java", 10);
		assertThat(co).hasSize(1);
		assertThat(co.get(0).path()).isEqualTo("B.java");
		assertThat(co.get(0).count()).isEqualTo(2);

		assertThat(gitService.recentCommits(id, 10).get(0).subject()).isEqualTo("third");

		write("C.java", "untracked");
		assertThat(gitService.workingChanges(id)).extracting(GitStatusParser.WorkingChange::path).contains("C.java");
	}

	private void write(String name, String content) throws IOException {
		Files.writeString(repo.resolve(name), content);
	}

	private void commit(String message) throws Exception {
		git("add", "-A");
		git("-c", "user.name=Test", "-c", "user.email=t@t.dev", "-c", "commit.gpgsign=false", "commit", "-m", message);
	}

	private void git(String... args) throws Exception {
		String[] cmd = new String[args.length + 1];
		cmd[0] = "git";
		System.arraycopy(args, 0, cmd, 1, args.length);
		Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
		p.getInputStream().readAllBytes();
		assertThat(p.waitFor()).as("git " + String.join(" ", args)).isZero();
	}

	private static boolean gitAvailable() {
		try {
			Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();
			return p.waitFor() == 0;
		} catch (Exception e) {
			return false;
		}
	}
}
