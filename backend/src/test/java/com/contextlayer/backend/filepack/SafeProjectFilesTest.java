package com.contextlayer.backend.filepack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.contextlayer.backend.common.BadRequestException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeProjectFilesTest {

	@TempDir
	Path tmp;

	Path root;
	Path outside;

	@BeforeEach
	void setUp() throws IOException {
		root = Files.createDirectories(tmp.resolve("project")).toRealPath();
		outside = Files.createDirectories(tmp.resolve("outside")).toRealPath();
		Files.writeString(outside.resolve("secret.txt"), "top secret");
		write("src/App.java", "class App {}\n");
	}

	private void write(String relative, String text) throws IOException {
		Path file = root.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
	}

	private void linkOrSkip(Path link, Path target) throws IOException {
		try {
			Files.createSymbolicLink(link, target);
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			assumeTrue(false, "Symbolic links are not available on this machine");
		}
	}

	@Test
	void rejectsAbsoluteAndTraversalPaths() {
		assertThatThrownBy(() -> SafeProjectFiles.requireRelative("/etc/passwd"))
			.isInstanceOf(BadRequestException.class)
			.hasMessageContaining("Absolute");
		assertThatThrownBy(() -> SafeProjectFiles.requireRelative("C:/Windows/win.ini"))
			.isInstanceOf(BadRequestException.class);
		assertThatThrownBy(() -> SafeProjectFiles.requireRelative("src/../../etc/passwd"))
			.isInstanceOf(BadRequestException.class)
			.hasMessageContaining("traversal");
		assertThatThrownBy(() -> SafeProjectFiles.requireRelative("src\\App.java"))
			.isInstanceOf(BadRequestException.class);
		assertThat(SafeProjectFiles.requireRelative("src/App.java")).isEqualTo("src/App.java");
	}

	@Test
	void blocksSensitiveFilesAndFolders() {
		for (String path : new String[] {
			".env",
			"config/.env.staging",
			"keys/id_rsa",
			"ops/server.pem",
			"a/credentials.json",
			"app/.git/config",
			"web/node_modules/x/index.js",
			"pgdata/base/1",
			"backend/target/classes/A.class"
		}) {
			assertThat(SafeProjectFiles.blockedReason(path)).as(path).isNotNull();
		}
		assertThat(SafeProjectFiles.blockedReason("src/main/App.java")).isNull();
	}

	@Test
	void readsAnOrdinaryFileUnchanged() {
		byte[] bytes = SafeProjectFiles.read(root, "src/App.java", 1024);
		assertThat(new String(bytes)).isEqualTo("class App {}\n");
	}

	@Test
	void refusesSensitiveFileEvenWhenItExists() throws IOException {
		write(".env", "A=1");
		assertThatThrownBy(() -> SafeProjectFiles.read(root, ".env", 1024)).isInstanceOf(BadRequestException.class);
	}

	@Test
	void refusesDirectoriesAndBinaryFiles() throws IOException {
		Files.write(root.resolve("src/data.bin"), new byte[] { 1, 0, 2 });
		assertThatThrownBy(() -> SafeProjectFiles.read(root, "src", 1024))
			.isInstanceOf(BadRequestException.class)
			.hasMessageContaining("Directories");
		assertThatThrownBy(() -> SafeProjectFiles.read(root, "src/data.bin", 1024))
			.isInstanceOf(BadRequestException.class)
			.hasMessageContaining("Binary");
	}

	@Test
	void enforcesTheFileSizeLimit() throws IOException {
		write("src/big.txt", "x".repeat(2000));
		assertThatThrownBy(() -> SafeProjectFiles.read(root, "src/big.txt", 1000))
			.isInstanceOf(BadRequestException.class)
			.hasMessageContaining("too large");
	}

	@Test
	void refusesSymbolicLinkThatEscapesTheProject() throws IOException {
		linkOrSkip(root.resolve("src/leak.txt"), outside.resolve("secret.txt"));
		assertThatThrownBy(() -> SafeProjectFiles.read(root, "src/leak.txt", 1024))
			.isInstanceOf(BadRequestException.class)
			.hasMessageContaining("Symbolic");
	}

	@Test
	void refusesFileBehindALinkedDirectory() throws IOException {
		linkOrSkip(root.resolve("linked"), outside);
		assertThatThrownBy(() -> SafeProjectFiles.read(root, "linked/secret.txt", 1024))
			.isInstanceOf(BadRequestException.class);
	}
}
