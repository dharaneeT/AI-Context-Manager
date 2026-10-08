package com.contextlayer.backend.indexing;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FileChunkRepository extends JpaRepository<FileChunk, Long> {}
