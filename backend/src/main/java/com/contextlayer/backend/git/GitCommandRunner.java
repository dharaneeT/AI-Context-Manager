package com.contextlayer.backend.git;

import com.contextlayer.backend.config.GitProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GitCommandRunner {

	private final GitProperties props;

	/** ".git" is a directory normally and a file in worktrees/submodules, so we only test existence. */
	public boolean isGitRepository(Path root) {
		return Files.exists(root.resolve(".git"));
	}

	public boolean hasCommits(Path root) {
		try {
			run(root, "rev-parse", "--verify", "HEAD");
			return true;
		} catch (GitException e) {
			return false;
		}
	}

	/** Runs `git -C root args...` and returns stdout. No shell is involved, so no injection risk. */
	public String run(Path root, String... args) {
		List<String> command = new ArrayList<>(List.of("git", "-c", "core.quotepath=false", "-C", root.toString()));
		command.addAll(Arrays.asList(args));

		Process process = start(command);
		CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> {
			try {
				return process.getInputStream().readAllBytes();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});

		String description = "git " + String.join(" ", args);
		try {
			byte[] bytes = stdout.get(props.timeoutSeconds(), TimeUnit.SECONDS);
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new GitException(description + " did not exit");
			}
			if (process.exitValue() != 0) {
				throw new GitException(description + " failed with exit code " + process.exitValue());
			}
			return new String(bytes, StandardCharsets.UTF_8);
		} catch (TimeoutException e) {
			process.destroyForcibly();
			throw new GitException(description + " timed out after " + props.timeoutSeconds() + "s");
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new GitException("Interrupted while running " + description, e);
		} catch (ExecutionException e) {
			process.destroyForcibly();
			throw new GitException("Could not read output of " + description, e);
		}
	}

	private Process start(List<String> command) {
		try {
			// stderr is discarded: if it were piped and never read, a chatty git could block forever
			return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		} catch (IOException e) {
			throw new GitException("Could not start git. Is it installed and on your PATH?", e);
		}
	}
}
