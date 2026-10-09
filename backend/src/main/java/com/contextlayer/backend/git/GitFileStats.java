package com.contextlayer.backend.git;

import com.contextlayer.backend.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(
	name = "git_file_stats",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_gitstats_project_path",
		columnNames = { "project_id", "relative_path" }
	)
)
@Getter
@Setter
@NoArgsConstructor
public class GitFileStats {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Project project;

	@Column(name = "relative_path", nullable = false, length = 1000)
	private String relativePath;

	/** Counts only commits inside the analysed window (contextlayer.git.max-commits). */
	@Column(name = "commit_count", nullable = false)
	private int commitCount;

	@Column(name = "author_count", nullable = false)
	private int authorCount;

	@Column(name = "lines_added", nullable = false)
	private long linesAdded;

	@Column(name = "lines_deleted", nullable = false)
	private long linesDeleted;

	@Column(name = "first_commit_at")
	private Instant firstCommitAt;

	@Column(name = "last_commit_at")
	private Instant lastCommitAt;

	@Column(name = "last_commit_hash", length = 40)
	private String lastCommitHash;

	@Column(name = "last_author", length = 200)
	private String lastAuthor;

	@Column(name = "last_subject", length = 500)
	private String lastSubject;

	/** Snapshots taken at sync time. Re-sync to refresh them. */
	@Column(name = "commits_last_30d", nullable = false)
	private int commitsLast30d;

	@Column(name = "commits_last_90d", nullable = false)
	private int commitsLast90d;
}
