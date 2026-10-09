package com.contextlayer.backend.indexing;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectFileRepository extends JpaRepository<ProjectFile, Long> {
	List<ProjectFile> findAllByProjectId(Long projectId);

	long countByProjectId(Long projectId);

	@Query(
		"""
            select new com.contextlayer.backend.indexing.LanguageStat(f.language, count(f), sum(f.sizeBytes))
            from ProjectFile f
            where f.project.id = :projectId
            group by f.language
            order by count(f) desc
            """
	)
	List<LanguageStat> languageStats(@Param("projectId") Long projectId);

	@Query("select f.relativePath from ProjectFile f where f.project.id = :projectId order by f.relativePath")
	List<String> findAllPaths(@Param("projectId") Long projectId);
}
