package com.contextlayer.backend.task;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {
	List<TaskRun> findByProjectIdOrderByCreatedAtDesc(Long projectId, Pageable pageable);

	@Transactional
	@Modifying
	@Query(
		"""
            update TaskRun t set t.status = com.contextlayer.backend.task.TaskStatus.FAILED,
                t.stage = 'Interrupted', t.errorMessage = 'Interrupted by an application restart',
                t.finishedAt = :now
            where t.status in (com.contextlayer.backend.task.TaskStatus.QUEUED,
                               com.contextlayer.backend.task.TaskStatus.RUNNING)
            """
	)
	int markInterrupted(@Param("now") Instant now);
}
