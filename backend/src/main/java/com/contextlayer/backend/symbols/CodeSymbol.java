package com.contextlayer.backend.symbols;

import com.contextlayer.backend.indexing.ProjectFile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "code_symbol", indexes = @Index(name = "idx_symbol_file", columnList = "file_id"))
@Getter
@Setter
@NoArgsConstructor
public class CodeSymbol {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "file_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private ProjectFile file;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SymbolKind kind;

	@Column(nullable = false, length = 300)
	private String name;

	@Column(name = "qualified_name", nullable = false, length = 500)
	private String qualifiedName;

	@Column(length = 600)
	private String signature;

	@Column(name = "start_line", nullable = false)
	private int startLine;

	@Column(name = "end_line", nullable = false)
	private int endLine;

	@Column(length = 500)
	private String doc;

	@Column(length = 400)
	private String annotations;
}
