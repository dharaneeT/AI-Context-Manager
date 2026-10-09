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
	name = "git_commit",
	uniqueConstraints = @UniqueConstraint(name = "uk_commit_project_hash", columnNames = { "project_id", "hash" })
)
@Getter
@Setter
@NoArgsConstructor
public class GitCommit {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Project project;

	@Column(nullable = false, length = 40)
	private String hash;

	@Column(length = 200)
	private String author;

	@Column(name = "committed_at", nullable = false)
	private Instant committedAt;

	@Column(length = 500)
	private String subject;

	@Column(name = "files_changed", nullable = false)
	private int filesChanged;
}
