package com.contextlayer.backend.agent;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {
	List<AgentTask> findByProjectIdOrderByCreatedAtDesc(Long projectId, Pageable pageable);

	Optional<AgentTask> findByIdAndProjectId(Long id, Long projectId);
}
