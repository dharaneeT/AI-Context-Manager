package com.contextlayer.backend.embedding;

import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.search.SearchResults.ChunkHit;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class VectorStore implements ApplicationRunner {

	public record Pending(long id, String path, String outline, String content) {}

	private final JdbcTemplate jdbc;
	private final EmbeddingProvider provider;
	private volatile boolean available;
	private volatile String reason = "not initialised yet";

	public VectorStore(JdbcTemplate jdbc, EmbeddingProvider provider) {
		this.jdbc = jdbc;
		this.provider = provider;
	}

	public boolean available() {
		return available;
	}

	public String unavailableReason() {
		return reason;
	}

	@Override
	public void run(ApplicationArguments args) {
		try {
			String product = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
			if (!"PostgreSQL".equalsIgnoreCase(product)) {
				reason = "database is " + product + ", not PostgreSQL";
				log.info("Vector store disabled: {}", reason);
				return;
			}
			jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");

			// For pgvector, pg_attribute.atttypmod IS the dimension count.
			List<Integer> existing = jdbc.queryForList(
				"""
                    select atttypmod from pg_attribute
                    where attrelid = to_regclass('chunk_embedding') and attname = 'embedding'
                    """,
				Integer.class
			);
			if (!existing.isEmpty() && existing.get(0) != provider.dimensions()) {
				log.warn(
					"Embedding dimension changed ({} -> {}). Dropping derived vectors; re-run embeddings refresh.",
					existing.get(0),
					provider.dimensions()
				);
				jdbc.execute("DROP TABLE chunk_embedding");
			}
			jdbc.execute(
				"""
                    CREATE TABLE IF NOT EXISTS chunk_embedding (
                        chunk_id bigint PRIMARY KEY REFERENCES file_chunk(id) ON DELETE CASCADE,
                        model varchar(100) NOT NULL,
                        embedding vector(%d) NOT NULL
                    )""".formatted(
						provider.dimensions()
					)
			);
			available = true;
			reason = null;
			log.info("Vector store ready ({} dimensions, model {})", provider.dimensions(), provider.modelId());
		} catch (DataAccessException e) {
			available = false;
			reason = "pgvector is not usable: " + NestedExceptionUtils.getMostSpecificCause(e).getMessage();
			log.warn("Vector store disabled. {}", reason);
		}
	}

	// ---- writes ----

	public void deleteOtherModels(String model) {
		jdbc.update("DELETE FROM chunk_embedding WHERE model <> ?", model);
	}

	public void saveChunkEmbeddings(String model, List<Long> chunkIds, List<float[]> vectors) {
		List<Object[]> args = new ArrayList<>();
		for (int i = 0; i < chunkIds.size(); i++) {
			args.add(new Object[] { chunkIds.get(i), model, literal(vectors.get(i)) });
		}
		jdbc.batchUpdate(
			"""
                INSERT INTO chunk_embedding (chunk_id, model, embedding) VALUES (?, ?, cast(? as vector))
                ON CONFLICT (chunk_id) DO UPDATE SET model = EXCLUDED.model, embedding = EXCLUDED.embedding
                """,
			args
		);
	}

	// ---- reads ----

	/** Chunks of this project that have no embedding for the current model yet. */
	public List<Pending> pendingChunks(Long projectId, String model, int limit) {
		return jdbc.query(
			"""
                SELECT c.id, f.relative_path, c.outline, c.content
                FROM file_chunk c JOIN project_file f ON f.id = c.file_id
                WHERE f.project_id = ?
                  AND NOT EXISTS (SELECT 1 FROM chunk_embedding e WHERE e.chunk_id = c.id AND e.model = ?)
                ORDER BY c.id LIMIT ?
                """,
			(rs, i) -> new Pending(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
			projectId,
			model,
			limit
		);
	}

	public List<ChunkHit> searchChunks(Long projectId, String model, float[] query, int limit) {
		return jdbc.query(
			"""
                SELECT c.id, f.relative_path, c.start_line, c.end_line, c.outline, c.content,
                       1 - (e.embedding <=> cast(? as vector)) AS similarity
                FROM chunk_embedding e
                JOIN file_chunk c ON c.id = e.chunk_id
                JOIN project_file f ON f.id = c.file_id
                WHERE f.project_id = ? AND e.model = ?
                ORDER BY similarity DESC
                LIMIT ?
                """,
			(rs, i) ->
				new ChunkHit(
					rs.getLong(1),
					rs.getString(2),
					rs.getInt(3),
					rs.getInt(4),
					rs.getDouble(7),
					rs.getString(5),
					preview(rs.getString(6))
				),
			literal(query),
			projectId,
			model,
			limit
		);
	}

	public long countChunks(Long projectId) {
		Long n = jdbc.queryForObject(
			"""
                SELECT count(*) FROM file_chunk c JOIN project_file f ON f.id = c.file_id WHERE f.project_id = ?
                """,
			Long.class,
			projectId
		);
		return n == null ? 0 : n;
	}

	public long countEmbedded(Long projectId, String model) {
		Long n = jdbc.queryForObject(
			"""
                SELECT count(*) FROM file_chunk c
                JOIN project_file f ON f.id = c.file_id
                JOIN chunk_embedding e ON e.chunk_id = c.id AND e.model = ?
                WHERE f.project_id = ?
                """,
			Long.class,
			model,
			projectId
		);
		return n == null ? 0 : n;
	}

	/** pgvector's text format: [0.1,0.2,...]. We pass it as a string and cast in SQL, so no driver extras are needed. */
	public static String literal(float[] v) {
		StringBuilder sb = new StringBuilder(v.length * 9).append('[');
		for (int i = 0; i < v.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			float x = v[i];
			sb.append(Float.isNaN(x) || Float.isInfinite(x) ? 0f : x);
		}
		return sb.append(']').toString();
	}

	private static String preview(String content) {
		return Text.truncate(String.join("\n", content.lines().limit(6).toList()), 400);
	}
}
