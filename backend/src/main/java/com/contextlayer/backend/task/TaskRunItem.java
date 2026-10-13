package com.contextlayer.backend.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "task_run_item")
@Getter
@Setter
@NoArgsConstructor
public class TaskRunItem {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "task_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private TaskRun task;

	@Column(nullable = false)
	private int position;

	@Column(nullable = false, length = 1000)
	private String path;

	@Column(nullable = false, length = 20)
	private String role;

	@Column(nullable = false, length = 20)
	private String level;

	@Column(nullable = false)
	private int tokens;

	@Column(nullable = false)
	private double score;

	/** Newline-separated. */
	@Column(columnDefinition = "text")
	private String reasons;
}
