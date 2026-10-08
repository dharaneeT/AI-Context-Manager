package com.contextlayer.backend.indexing;

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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(
	name = "project_file",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_file_project_path",
		columnNames = { "project_id", "relative_path" }
	)
)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProjectFile {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Project project;

	/** Always uses '/', relative to the project root, e.g. "src/main/java/App.java". */
	@Column(name = "relative_path", nullable = false, length = 1000)
	private String relativePath;

	@Column(length = 30)
	private String extension;

	@Column(length = 50)
	private String language;

	@Column(name = "size_bytes", nullable = false)
	private long sizeBytes;

	@Column(name = "line_count", nullable = false)
	private int lineCount;

	@Column(name = "content_hash", nullable = false, length = 64)
	private String contentHash;

	@Column(name = "last_modified", nullable = false)
	private Instant lastModified;

	@Column(name = "indexed_at", nullable = false)
	private Instant indexedAt;
}
