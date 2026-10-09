package com.contextlayer.backend.git;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GitCoChangeRepository extends JpaRepository<GitCoChange, Long> {
	@Modifying
	@Query("delete from GitCoChange c where c.project.id = :projectId")
	void deleteByProjectId(@Param("projectId") Long projectId);

	@Query(
		"""
            select c from GitCoChange c
            where c.project.id = :projectId and (c.pathA = :path or c.pathB = :path)
            order by c.pairCount desc
            """
	)
	List<GitCoChange> findForPath(@Param("projectId") Long projectId, @Param("path") String path, Pageable pageable);
}
