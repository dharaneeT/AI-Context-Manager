package com.contextlayer.backend.indexing;

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
	name = "file_chunk",
	uniqueConstraints = @UniqueConstraint(name = "uk_chunk_file_index", columnNames = { "file_id", "chunk_index" })
)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FileChunk {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "file_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private ProjectFile file;

	@Column(name = "chunk_index", nullable = false)
	private int chunkIndex;

	/** 1-based, inclusive line numbers. */
	@Column(name = "start_line", nullable = false)
	private int startLine;

	@Column(name = "end_line", nullable = false)
	private int endLine;

	@Column(nullable = false, columnDefinition = "text")
	private String content;

	@Column(name = "token_estimate", nullable = false)
	private int tokenEstimate;

	@Column(name = "content_hash", nullable = false, length = 64)
	private String contentHash;
}
