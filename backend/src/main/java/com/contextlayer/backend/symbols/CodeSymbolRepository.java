package com.contextlayer.backend.symbols;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CodeSymbolRepository extends JpaRepository<CodeSymbol, Long> {
	@Modifying
	@Query("delete from CodeSymbol s where s.file.id = :fileId")
	void deleteByFileId(@Param("fileId") Long fileId);

	@Query(
		"""
            select s from CodeSymbol s join fetch s.file f
            where f.project.id = :projectId order by f.id, s.startLine
            """
	)
	List<CodeSymbol> findAllByProjectId(@Param("projectId") Long projectId);

	@Query(
		"""
            select s from CodeSymbol s
            where s.file.project.id = :projectId and s.file.relativePath = :path order by s.startLine
            """
	)
	List<CodeSymbol> findByPath(@Param("projectId") Long projectId, @Param("path") String path);

	/** Top-level types only: their qualified name has no dot. */
	@Query(
		"""
            select s from CodeSymbol s join fetch s.file f
            where f.project.id = :projectId and s.kind in :kinds and s.qualifiedName = s.name
            """
	)
	List<CodeSymbol> findTopLevelTypes(
		@Param("projectId") Long projectId,
		@Param("kinds") Collection<SymbolKind> kinds
	);

	@Query(
		"""
            select s from CodeSymbol s join fetch s.file f
            where f.project.id = :projectId and lower(s.name) like concat('%', :keyword, '%')
            order by s.name
            """
	)
	List<CodeSymbol> searchByName(
		@Param("projectId") Long projectId,
		@Param("keyword") String keyword,
		Pageable pageable
	);

	@Query(
		"""
            select distinct f.relativePath from CodeSymbol s join s.file f
            where f.project.id = :projectId
              and (s.annotations like '%SpringBootApplication%'
                   or (s.name = 'main' and s.kind in (com.contextlayer.backend.symbols.SymbolKind.METHOD,
                                                       com.contextlayer.backend.symbols.SymbolKind.FUNCTION)))
            order by f.relativePath
            """
	)
	List<String> findEntryPointPaths(@Param("projectId") Long projectId);
}
