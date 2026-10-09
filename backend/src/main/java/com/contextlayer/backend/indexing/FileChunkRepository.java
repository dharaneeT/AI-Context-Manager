package com.contextlayer.backend.indexing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FileChunkRepository extends JpaRepository<FileChunk, Long> {
	long countByFileProjectId(Long projectId);

	@Query("select sum(c.tokenEstimate) from FileChunk c where c.file.project.id = :projectId")
	Long sumTokens(@Param("projectId") Long projectId);

	/** One SQL DELETE, instead of loading every chunk entity just to delete it. */
	@Modifying
	@Query("delete from FileChunk c where c.file.id = :fileId")
	void deleteByFileId(@Param("fileId") Long fileId);
}
