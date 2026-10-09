package com.contextlayer.backend.graph;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FileDependencyRepository extends JpaRepository<FileDependency, Long> {
	@Modifying
	@Query(
		"""
            delete from FileDependency d
            where d.fromFile.id in (select f.id from ProjectFile f where f.project.id = :projectId)
            """
	)
	void deleteByProjectId(@Param("projectId") Long projectId);

	@Query(
		"""
            select new com.contextlayer.backend.graph.GraphEdge(d.fromFile.relativePath, d.toFile.relativePath, d.kind)
            from FileDependency d
            where d.fromFile.project.id = :projectId and d.toFile is not null
            """
	)
	List<GraphEdge> findResolvedEdges(@Param("projectId") Long projectId);

	@Query(
		"""
            select d.toFile.relativePath from FileDependency d
            where d.fromFile.project.id = :projectId and d.fromFile.relativePath = :path and d.toFile is not null
            order by d.toFile.relativePath
            """
	)
	List<String> findDependencies(@Param("projectId") Long projectId, @Param("path") String path);

	@Query(
		"""
            select d.fromFile.relativePath from FileDependency d
            where d.fromFile.project.id = :projectId and d.toFile.relativePath = :path
            order by d.fromFile.relativePath
            """
	)
	List<String> findDependents(@Param("projectId") Long projectId, @Param("path") String path);

	@Query(
		"""
            select new com.contextlayer.backend.graph.FileDegree(d.toFile.relativePath, count(d))
            from FileDependency d
            where d.fromFile.project.id = :projectId and d.toFile is not null
            group by d.toFile.relativePath
            order by count(d) desc
            """
	)
	List<FileDegree> mostDependedOn(@Param("projectId") Long projectId, Pageable pageable);

	@Query(
		"""
            select new com.contextlayer.backend.graph.ExternalImport(d.specifier, count(d))
            from FileDependency d
            where d.fromFile.project.id = :projectId and d.toFile is null
            group by d.specifier
            order by count(d) desc
            """
	)
	List<ExternalImport> topExternal(@Param("projectId") Long projectId, Pageable pageable);
}
