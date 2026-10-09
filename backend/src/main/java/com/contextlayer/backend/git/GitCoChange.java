package com.contextlayer.backend.git;

import com.contextlayer.backend.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** Two files edited in the same commit pair_count times. path_a is always alphabetically before path_b. */
@Entity
@Table(
	name = "git_co_change",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_cochange_pair",
		columnNames = { "project_id", "path_a", "path_b" }
	),
	indexes = @Index(name = "idx_cochange_b", columnList = "project_id,path_b")
)
@Getter
@Setter
@NoArgsConstructor
public class GitCoChange {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Project project;

	@Column(name = "path_a", nullable = false, length = 1000)
	private String pathA;

	@Column(name = "path_b", nullable = false, length = 1000)
	private String pathB;

	@Column(name = "pair_count", nullable = false)
	private int pairCount;
}
