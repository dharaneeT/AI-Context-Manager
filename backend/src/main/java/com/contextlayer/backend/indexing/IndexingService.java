package com.contextlayer.backend.indexing;

import com.contextlayer.backend.common.BadRequestException;
import com.contextlayer.backend.common.ConflictException;
import com.contextlayer.backend.common.HashUtils;
import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.indexing.chunk.Chunk;
import com.contextlayer.backend.indexing.chunk.Chunker;
import com.contextlayer.backend.indexing.scan.ProjectScanner;
import com.contextlayer.backend.indexing.scan.ScanResult;
import com.contextlayer.backend.indexing.scan.ScannedFile;
import com.contextlayer.backend.project.IndexStatus;
import com.contextlayer.backend.project.Project;
import com.contextlayer.backend.project.ProjectRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingService {

	private final ProjectRepository projectRepository;
	private final ProjectFileRepository fileRepository;
	private final FileChunkRepository chunkRepository;
	private final ProjectScanner scanner;
	private final Chunker chunker;
	private final TransactionTemplate transactionTemplate;

	/** Projects currently being indexed. In-memory, so it assumes one app instance. */
	private final Set<Long> running = ConcurrentHashMap.newKeySet();

	public IndexResult indexProject(Long projectId) {
		if (!projectRepository.existsById(projectId)) {
			throw new NotFoundException("Project " + projectId + " not found");
		}
		if (!running.add(projectId)) {
			throw new ConflictException("Indexing is already running for project " + projectId);
		}
		long started = System.currentTimeMillis();
		try {
			// One transaction = all-or-nothing. A crash halfway leaves the DB exactly as before.
			return transactionTemplate.execute(status -> doIndex(projectId, started));
		} catch (RuntimeException e) {
			markFailed(projectId);
			throw e;
		} finally {
			running.remove(projectId);
		}
	}

	private IndexResult doIndex(Long projectId, long startedMillis) {
		Project project = projectRepository
			.findById(projectId)
			.orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
		Path root = Path.of(project.getRootPath());
		if (!Files.isDirectory(root)) {
			throw new BadRequestException("Project folder no longer exists: " + root);
		}

		ScanResult scan = scanner.scan(root);
		Map<String, ProjectFile> existing = fileRepository
			.findAllByProjectId(projectId)
			.stream()
			.collect(Collectors.toMap(ProjectFile::getRelativePath, Function.identity()));

		int added = 0, updated = 0, unchanged = 0, failed = 0, chunksWritten = 0;
		Instant now = Instant.now();

		for (ScannedFile scanned : scan.files()) {
			// remove() so that whatever is LEFT in `existing` afterwards is exactly the deleted files
			ProjectFile current = existing.remove(scanned.relativePath());
			try {
				// Fast path: same size and mtime means we don't even open the file.
				if (current != null && sameStat(current, scanned)) {
					unchanged++;
					continue;
				}

				byte[] bytes = Files.readAllBytes(scanned.absolutePath());
				String hash = HashUtils.sha256Hex(bytes);

				// Touched but identical (e.g. git checkout): record the new mtime, keep the chunks.
				if (current != null && current.getContentHash().equals(hash)) {
					current.setLastModified(scanned.lastModified());
					unchanged++;
					continue;
				}

				String content = new String(bytes, StandardCharsets.UTF_8);
				List<Chunk> chunks = chunker.chunk(content);
				int lineCount = (int) content.lines().count();

				ProjectFile file;
				if (current == null) {
					file =
						fileRepository.save(
							ProjectFile
								.builder()
								.project(project)
								.relativePath(scanned.relativePath())
								.extension(scanned.extension())
								.language(scanned.language())
								.sizeBytes(scanned.sizeBytes())
								.lineCount(lineCount)
								.contentHash(hash)
								.lastModified(scanned.lastModified())
								.indexedAt(now)
								.build()
						);
					added++;
				} else {
					file = current;
					file.setExtension(scanned.extension());
					file.setLanguage(scanned.language());
					file.setSizeBytes(scanned.sizeBytes());
					file.setLineCount(lineCount);
					file.setContentHash(hash);
					file.setLastModified(scanned.lastModified());
					file.setIndexedAt(now);
					chunkRepository.deleteByFileId(file.getId());
					updated++;
				}

				chunkRepository.saveAll(toEntities(file, chunks));
				chunksWritten += chunks.size();
			} catch (IOException e) {
				// One unreadable file must not abort the whole run. Its old rows stay as they were.
				log.warn("Skipping {}: {}", scanned.relativePath(), e.getMessage());
				failed++;
			}
		}

		int deleted = existing.size();
		fileRepository.deleteAll(existing.values()); // chunks go with them via ON DELETE CASCADE

		project.setIndexStatus(IndexStatus.READY);
		project.setLastIndexedAt(now);

		long duration = System.currentTimeMillis() - startedMillis;
		log.info(
			"Indexed project {}: +{} ~{} ={} -{} (failed {}), {} chunks, {} ms",
			projectId,
			added,
			updated,
			unchanged,
			deleted,
			failed,
			chunksWritten,
			duration
		);

		return new IndexResult(
			scan.files().size(),
			added,
			updated,
			unchanged,
			deleted,
			failed,
			chunksWritten,
			scan.skipped(),
			duration
		);
	}

	private static boolean sameStat(ProjectFile f, ScannedFile s) {
		return f.getSizeBytes() == s.sizeBytes() && f.getLastModified().equals(s.lastModified());
	}

	private static List<FileChunk> toEntities(ProjectFile file, List<Chunk> chunks) {
		List<FileChunk> result = new ArrayList<>(chunks.size());
		for (Chunk c : chunks) {
			result.add(
				FileChunk
					.builder()
					.file(file)
					.chunkIndex(c.index())
					.startLine(c.startLine())
					.endLine(c.endLine())
					.content(c.content())
					.tokenEstimate(c.tokenEstimate())
					.contentHash(c.contentHash())
					.build()
			);
		}
		return result;
	}

	private void markFailed(Long projectId) {
		try {
			transactionTemplate.executeWithoutResult(status ->
				projectRepository.findById(projectId).ifPresent(p -> p.setIndexStatus(IndexStatus.FAILED))
			);
		} catch (RuntimeException e) {
			log.warn("Could not mark project {} as FAILED: {}", projectId, e.getMessage());
		}
	}

	@Transactional(readOnly = true)
	public ProjectStats stats(Long projectId) {
		Project project = projectRepository
			.findById(projectId)
			.orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
		Long tokens = chunkRepository.sumTokens(projectId);
		return new ProjectStats(
			project.getIndexStatus(),
			project.getLastIndexedAt(),
			fileRepository.countByProjectId(projectId),
			chunkRepository.countByFileProjectId(projectId),
			tokens == null ? 0 : tokens,
			fileRepository.languageStats(projectId)
		);
	}
}
