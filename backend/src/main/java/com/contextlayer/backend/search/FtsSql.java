package com.contextlayer.backend.search;

final class FtsSql {

    private FtsSql() {
    }

    /** Inserts a space between a lowercase/digit and an uppercase letter ("authTimeout" -> "auth Timeout"). */
    static String vector(String column) {
        return "to_tsvector('simple', regexp_replace(" + column + ", '([a-z0-9])([A-Z])', '\\1 \\2', 'g'))";
    }

    static final String CHUNK_INDEX = "CREATE INDEX IF NOT EXISTS idx_chunk_fts ON file_chunk USING GIN ("
            + vector("content") + ")";

    static final String SUMMARY_INDEX = "CREATE INDEX IF NOT EXISTS idx_summary_fts ON file_summary USING GIN ("
            + vector("summary") + ")";

    static final String CHUNK_QUERY = """
            select c.id, f.relative_path, c.start_line, c.end_line, c.content, c.outline,
                   ts_rank(%1$s, to_tsquery('simple', :query), 1) as score
            from file_chunk c
            join project_file f on f.id = c.file_id
            where f.project_id = :projectId
              and %1$s @@ to_tsquery('simple', :query)
            order by score desc
            limit :limit
            """.formatted(vector("c.content"));

    static final String SUMMARY_QUERY = """
            select f.relative_path, s.summary,
                   ts_rank(%1$s, to_tsquery('simple', :query), 1) as score
            from file_summary s
            join project_file f on f.id = s.file_id
            where f.project_id = :projectId
              and %1$s @@ to_tsquery('simple', :query)
            order by score desc
            limit :limit
            """.formatted(vector("s.summary"));
}