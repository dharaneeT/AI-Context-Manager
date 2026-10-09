package com.contextlayer.backend.summary;

import com.contextlayer.backend.indexing.ProjectFile;
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
@Table(name = "file_summary", uniqueConstraints = @UniqueConstraint(name = "uk_summary_file", columnNames = "file_id"))
@Getter
@Setter
@NoArgsConstructor
public class FileSummary {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "file_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private ProjectFile file;

	@Column(nullable = false, columnDefinition = "text")
	private String summary;

	/** Content hash of the file when this summary was written. Equal to the file's hash => still fresh. */
	@Column(name = "source_hash", nullable = false, length = 64)
	private String sourceHash;

	@Column(nullable = false, length = 30)
	private String generator;

	@Column(name = "generated_at", nullable = false)
	private Instant generatedAt;
}
