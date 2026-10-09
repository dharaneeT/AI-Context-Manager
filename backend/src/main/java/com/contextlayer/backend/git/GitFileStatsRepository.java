package com.contextlayer.backend.git;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GitFileStatsRepository extends JpaRepository<GitFileStats, Long> {
	@Modifying
	@Query("delete from GitFileStats s where s.project.id = :projectId")
	void deleteByProjectId(@Param("projectId") Long projectId);

	@Query(
		"""
            select s from GitFileStats s where s.project.id = :projectId
            order by s.commitsLast30d desc, s.commitCount desc, s.relativePath
            """
	)
	List<GitFileStats> findHot(@Param("projectId") Long projectId, Pageable pageable);

	List<GitFileStats> findByProjectId(Long projectId); // used by the ranking engine on Day 13
}
