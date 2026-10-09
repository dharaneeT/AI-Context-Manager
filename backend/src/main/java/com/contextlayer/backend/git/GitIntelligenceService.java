package com.contextlayer.backend.git;

import com.contextlayer.backend.common.BadRequestException;
import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.config.GitProperties;
import com.contextlayer.backend.git.GitLogParser.FileChange;
import com.contextlayer.backend.git.GitLogParser.ParsedCommit;
import com.contextlayer.backend.git.GitResponses.CoChange;
import com.contextlayer.backend.git.GitResponses.HotFile;
import com.contextlayer.backend.git.GitResponses.RecentCommit;
import com.contextlayer.backend.git.GitResponses.SyncResult;
import com.contextlayer.backend.project.Project;
import com.contextlayer.backend.project.ProjectRepository;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitIntelligenceService {

	private final ProjectRepository projectRepository;
	private final GitCommandRunner runner;
	private final GitProperties props;
	private final GitFileStatsRepository statsRepository;
	private final GitCommitRepository commitRepository;
	private final GitCoChangeRepository coChangeRepository;

	private record PathPair(String a, String b) {}

	/** Running totals for one file while we walk the history. */
	private static final class FileAgg {

		int commits;
		long added;
		long deleted;
		final Set<String> authors = new HashSet<>();
		Instant first;
		Instant last;
		String lastHash;
		String lastAuthor;
		String lastSubject;
		int last30;
		int last90;

		void accept(ParsedCommit c, FileChange f, Instant d30, Instant d90) {
			commits++;
			added += f.added();
			deleted += f.deleted();
			authors.add(c.author());
			if (last == null || c.committedAt().isAfter(last)) {
				last = c.committedAt();
				lastHash = c.hash();
				lastAuthor = c.author();
				lastSubject = c.subject();
			}
			if (first == null || c.committedAt().isBefore(first)) {
				first = c.committedAt();
			}
			if (c.committedAt().isAfter(d30)) {
				last30++;
			}
			if (c.committedAt().isAfter(d90)) {
				last90++;
			}
		}
	}

	/** Re-analyses the history from scratch. Cheap enough (one git call) that incremental isn't worth it. */
	@Transactional
	public SyncResult sync(Long projectId) {
		Project project = getProject(projectId);
		Path root = Path.of(project.getRootPath());
		requireRepository(root);

		List<ParsedCommit> commits = runner.hasCommits(root)
			? GitLogParser.parse(
				runner.run(
					root,
					"log",
					"--numstat",
					"--no-renames",
					"--max-count=" + props.maxCommits(),
					"--pretty=format:%x1e%H%x1f%an%x1f%cI%x1f%s"
				)
			)
			: List.of();

		Instant now = Instant.now();
		Instant d30 = now.minus(30, ChronoUnit.DAYS);
		Instant d90 = now.minus(90, ChronoUnit.DAYS);
		Map<String, Boolean> existsCache = new HashMap<>();

		Map<String, FileAgg> perFile = new LinkedHashMap<>();
		Map<PathPair, Integer> pairs = new HashMap<>();

		for (ParsedCommit commit : commits) {
			List<String> alive = new ArrayList<>();
			for (FileChange change : commit.files()) {
				// History mentions files that were deleted long ago. We only track files that exist now.
				if (!existsCache.computeIfAbsent(change.path(), p -> existsNow(root, p))) {
					continue;
				}
				perFile.computeIfAbsent(change.path(), k -> new FileAgg()).accept(commit, change, d30, d90);
				alive.add(change.path());
			}
			List<String> sorted = alive.stream().distinct().sorted().toList();
			boolean reasonableSize = commit.files().size() <= props.maxFilesPerCommitForCoChange();
			if (reasonableSize && sorted.size() >= 2) {
				for (int i = 0; i < sorted.size(); i++) {
					for (int j = i + 1; j < sorted.size(); j++) {
						pairs.merge(new PathPair(sorted.get(i), sorted.get(j)), 1, Integer::sum);
					}
				}
			}
		}

		statsRepository.deleteByProjectId(projectId);
		commitRepository.deleteByProjectId(projectId);
		coChangeRepository.deleteByProjectId(projectId);

		List<GitFileStats> statsRows = new ArrayList<>();
		perFile.forEach((path, agg) -> statsRows.add(toStats(project, path, agg)));
		statsRepository.saveAll(statsRows);

		commitRepository.saveAll(commits.stream().map(c -> toCommit(project, c)).toList());

		List<GitCoChange> coRows = new ArrayList<>();
		pairs.forEach((pair, count) -> {
			if (count >= props.minCoChangeCount()) {
				GitCoChange row = new GitCoChange();
				row.setProject(project);
				row.setPathA(pair.a());
				row.setPathB(pair.b());
				row.setPairCount(count);
				coRows.add(row);
			}
		});
		coChangeRepository.saveAll(coRows);

		log.info(
			"Git sync for project {}: {} commits, {} files, {} co-change pairs",
			projectId,
			commits.size(),
			statsRows.size(),
			coRows.size()
		);
		return new SyncResult(commits.size(), statsRows.size(), coRows.size());
	}

	@Transactional(readOnly = true)
	public List<HotFile> hotFiles(Long projectId, int limit) {
		return statsRepository
			.findHot(projectId, PageRequest.of(0, clamp(limit)))
			.stream()
			.map(s ->
				new HotFile(
					s.getRelativePath(),
					s.getCommitCount(),
					s.getCommitsLast30d(),
					s.getCommitsLast90d(),
					s.getAuthorCount(),
					s.getLastCommitAt(),
					s.getLastAuthor(),
					s.getLastSubject()
				)
			)
			.toList();
	}

	@Transactional(readOnly = true)
	public List<RecentCommit> recentCommits(Long projectId, int limit) {
		return commitRepository
			.findByProjectIdOrderByCommittedAtDesc(projectId, PageRequest.of(0, clamp(limit)))
			.stream()
			.map(c ->
				new RecentCommit(c.getHash(), c.getAuthor(), c.getCommittedAt(), c.getSubject(), c.getFilesChanged())
			)
			.toList();
	}

	@Transactional(readOnly = true)
	public List<CoChange> coChanges(Long projectId, String path, int limit) {
		return coChangeRepository
			.findForPath(projectId, path, PageRequest.of(0, clamp(limit)))
			.stream()
			.map(c -> new CoChange(c.getPathA().equals(path) ? c.getPathB() : c.getPathA(), c.getPairCount()))
			.toList();
	}

	/** Uncommitted changes: usually exactly what the developer is working on right now. */
	@Transactional(readOnly = true)
	public List<GitStatusParser.WorkingChange> workingChanges(Long projectId) {
		Path root = Path.of(getProject(projectId).getRootPath());
		requireRepository(root);
		return GitStatusParser.parse(runner.run(root, "status", "--porcelain=v1", "-z", "--untracked-files=all"));
	}

	// ---- helpers ----

	private Project getProject(Long projectId) {
		return projectRepository
			.findById(projectId)
			.orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
	}

	private void requireRepository(Path root) {
		if (!runner.isGitRepository(root)) {
			throw new BadRequestException("Project folder is not a Git repository: " + root);
		}
	}

	private static boolean existsNow(Path root, String relative) {
		try {
			return Files.isRegularFile(root.resolve(relative));
		} catch (InvalidPathException e) {
			return false;
		}
	}

	private static int clamp(int limit) {
		return Math.min(Math.max(limit, 1), 200);
	}

	private static GitFileStats toStats(Project project, String path, FileAgg a) {
		GitFileStats s = new GitFileStats();
		s.setProject(project);
		s.setRelativePath(path);
		s.setCommitCount(a.commits);
		s.setAuthorCount(a.authors.size());
		s.setLinesAdded(a.added);
		s.setLinesDeleted(a.deleted);
		s.setFirstCommitAt(a.first);
		s.setLastCommitAt(a.last);
		s.setLastCommitHash(a.lastHash);
		s.setLastAuthor(Text.truncate(a.lastAuthor, 200));
		s.setLastSubject(Text.truncate(a.lastSubject, 500));
		s.setCommitsLast30d(a.last30);
		s.setCommitsLast90d(a.last90);
		return s;
	}

	private static GitCommit toCommit(Project project, ParsedCommit c) {
		GitCommit row = new GitCommit();
		row.setProject(project);
		row.setHash(c.hash());
		row.setAuthor(Text.truncate(c.author(), 200));
		row.setCommittedAt(c.committedAt());
		row.setSubject(Text.truncate(c.subject(), 500));
		row.setFilesChanged(c.files().size());
		return row;
	}
}
