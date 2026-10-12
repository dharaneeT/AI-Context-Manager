package com.contextlayer.backend.embedding;

import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.config.EmbeddingProperties;
import com.contextlayer.backend.embedding.VectorStore.Pending;
import com.contextlayer.backend.project.ProjectRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Deliberately NOT @Transactional: each batch commits on its own, so a crash or timeout loses at most one batch. */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

	public record EmbedResult(int chunksEmbedded, String model, int dimensions, long durationMs) {}

	public record EmbeddingStatus(
		String provider,
		String model,
		int dimensions,
		boolean available,
		String unavailableReason,
		long chunks,
		long embedded
	) {}

	private final ProjectRepository projectRepository;
	private final VectorStore store;
	private final EmbeddingProvider provider;
	private final EmbeddingProperties props;

	public EmbedResult refresh(Long projectId) {
		requireProject(projectId);
		if (!store.available()) {
			throw new EmbeddingException("Embeddings unavailable: " + store.unavailableReason());
		}
		long started = System.currentTimeMillis();
		String model = provider.modelId();
		store.deleteOtherModels(model);

		int embedded = 0;
		while (true) {
			List<Pending> batch = store.pendingChunks(projectId, model, props.batchSize());
			if (batch.isEmpty()) {
				break;
			}
			List<float[]> vectors = provider.embed(batch.stream().map(this::documentText).toList());
			if (vectors.size() != batch.size()) {
				throw new EmbeddingException(
					"Provider returned " + vectors.size() + " vectors for " + batch.size() + " texts"
				);
			}
			store.saveChunkEmbeddings(model, batch.stream().map(Pending::id).toList(), vectors);
			embedded += batch.size();
			log.debug("Embedded {} chunks so far", embedded);
		}
		long ms = System.currentTimeMillis() - started;
		log.info("Embedded {} chunks for project {} with {} in {} ms", embedded, projectId, model, ms);
		return new EmbedResult(embedded, model, provider.dimensions(), ms);
	}

	public EmbeddingStatus status(Long projectId) {
		requireProject(projectId);
		boolean ok = store.available();
		return new EmbeddingStatus(
			props.provider(),
			provider.modelId(),
			provider.dimensions(),
			ok,
			ok ? null : store.unavailableReason(),
			ok ? store.countChunks(projectId) : 0,
			ok ? store.countEmbedded(projectId, provider.modelId()) : 0
		);
	}

	/** Path and symbols first: this "contextual prefix" lets a chunk embed WHERE it lives, not just what it says. */
	private String documentText(Pending p) {
		StringBuilder sb = new StringBuilder(prefixed(props.documentPrefix()))
			.append("File: ")
			.append(p.path())
			.append('\n');
		if (p.outline() != null) {
			sb.append("Symbols: ").append(p.outline()).append('\n');
		}
		sb.append(p.content());
		return Text.truncate(sb.toString(), props.maxInputChars());
	}

	public static String prefixed(String prefix) {
		return prefix == null || prefix.isBlank() ? "" : prefix.strip() + " ";
	}

	private void requireProject(Long projectId) {
		if (!projectRepository.existsById(projectId)) {
			throw new NotFoundException("Project " + projectId + " not found");
		}
	}
}
