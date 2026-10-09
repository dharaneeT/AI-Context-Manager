package com.contextlayer.backend.git;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GitCommitRepository extends JpaRepository<GitCommit, Long> {
	@Modifying
	@Query("delete from GitCommit c where c.project.id = :projectId")
	void deleteByProjectId(@Param("projectId") Long projectId);

	List<GitCommit> findByProjectIdOrderByCommittedAtDesc(Long projectId, Pageable pageable);
}
