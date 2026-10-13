package com.contextlayer.backend.task;

import com.contextlayer.backend.project.Project;
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
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "task_run", indexes = @Index(name = "idx_task_project_created", columnList = "project_id,created_at"))
@Getter
@Setter
@NoArgsConstructor
public class TaskRun {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Project project;

	@Column(nullable = false, columnDefinition = "text")
	private String task;

	@Column(name = "max_tokens")
	private Integer maxTokens;

	@Column(name = "answer_requested", nullable = false)
	private boolean answerRequested;

	@Column(nullable = false)
	private boolean explain;

	@Column(name = "recent_activity", nullable = false)
	private boolean recentActivity;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private TaskStatus status = TaskStatus.QUEUED;

	@Column(length = 100)
	private String stage;

	@Column(name = "error_message", length = 1000)
	private String errorMessage;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	// ---- the pack ----
	@Column(name = "pack_markdown", columnDefinition = "text")
	private String packMarkdown;

	@Column(name = "pack_tokens")
	private Integer packTokens;

	@Column(name = "target_tokens")
	private Integer targetTokens;

	@Column(name = "within_budget")
	private Boolean withinBudget;

	@Column(name = "project_tokens")
	private Long projectTokens;

	@Column(name = "reduction_percent")
	private Double reductionPercent;

	@Column(name = "semantic_used")
	private Boolean semanticUsed;

	@Column(columnDefinition = "text")
	private String notes;

	// ---- the optional answer ----
	@Column(columnDefinition = "text")
	private String answer;

	@Column(name = "answer_model", length = 100)
	private String answerModel;

	@Column(name = "answer_input_tokens")
	private Integer answerInputTokens;

	@Column(name = "answer_output_tokens")
	private Integer answerOutputTokens;

	@Column(name = "answer_error", length = 1000)
	private String answerError;
}
