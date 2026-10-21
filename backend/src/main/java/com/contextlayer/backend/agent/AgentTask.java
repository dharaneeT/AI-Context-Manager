package com.contextlayer.backend.agent;

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
import org.hibernate.annotations.UpdateTimestamp;

/** One coding task given to the agent. Its id is also the run id inside the Python service. */
@Entity
@Table(name = "agent_task", indexes = @Index(name = "idx_agent_task_project_created", columnList = "project_id,created_at"))
@Getter
@Setter
@NoArgsConstructor
public class AgentTask {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Project project;

	@Column(nullable = false, columnDefinition = "text")
	private String command;

	@Column(name = "max_tokens")
	private Integer maxTokens;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AgentTaskStatus status = AgentTaskStatus.QUEUED;

	@Column(length = 100)
	private String stage;

	@Column(name = "error_message", length = 1000)
	private String errorMessage;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at")
	private Instant updatedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	// ---- the context pack the agent built (Day 5) ----
	@Column(name = "base_commit", length = 64)
	private String baseCommit;

	// ---- the plan and the decision about it (Days 9 and 10) ----
	@Column(name = "plan_markdown", columnDefinition = "text")
	private String planMarkdown;

	@Column(name = "plan_version")
	private Integer planVersion;

	@Column(name = "plan_generated_by", length = 100)
	private String planGeneratedBy;

	@Column(name = "decided_at")
	private Instant decidedAt;

	@Column(name = "decision_note", length = 1000)
	private String decisionNote;

	@Column(name = "pack_markdown", columnDefinition = "text")
	private String packMarkdown;

	@Column(name = "pack_tokens")
	private Integer packTokens;

	@Column(name = "full_files")
	private Integer fullFiles;

	@Column(name = "windowed_files")
	private Integer windowedFiles;

	@Column(name = "path_only_files")
	private Integer pathOnlyFiles;
}