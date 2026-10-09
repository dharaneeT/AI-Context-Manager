package com.contextlayer.backend.summary;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FileSummaryRepository extends JpaRepository<FileSummary, Long> {
	@Query("select s from FileSummary s join fetch s.file f where f.project.id = :projectId")
	List<FileSummary> findAllByProjectId(@Param("projectId") Long projectId);

	@Query(
		"""
            select s from FileSummary s join fetch s.file f
            where f.project.id = :projectId and f.relativePath = :path
            """
	)
	Optional<FileSummary> findByPath(@Param("projectId") Long projectId, @Param("path") String path);
}
