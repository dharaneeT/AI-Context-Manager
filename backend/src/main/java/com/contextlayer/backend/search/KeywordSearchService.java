package com.contextlayer.backend.search;

import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.indexing.ProjectFileRepository;
import com.contextlayer.backend.project.ProjectRepository;
import com.contextlayer.backend.search.SearchResults.ChunkHit;
import com.contextlayer.backend.search.SearchResults.KeywordSearchResult;
import com.contextlayer.backend.search.SearchResults.NameHit;
import com.contextlayer.backend.search.SearchResults.SummaryHit;
import com.contextlayer.backend.symbols.CodeSymbol;
import com.contextlayer.backend.symbols.CodeSymbolRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KeywordSearchService {

    private final EntityManager entityManager;
    private final KeywordExtractor extractor;
    private final ProjectRepository projectRepository;
    private final ProjectFileRepository fileRepository;
    private final CodeSymbolRepository symbolRepository;

    @Transactional(readOnly = true)
    public KeywordSearchResult search(Long projectId, String text, int limit) {
        if (!projectRepository.existsById(projectId)) {
            throw new NotFoundException("Project " + projectId + " not found");
        }
        int max = Math.min(Math.max(limit, 1), 50);
        List<String> keywords = extractor.extract(text, 12);
        if (keywords.isEmpty()) {
            return new KeywordSearchResult(keywords, List.of(), List.of(), List.of());
        }
        String tsQuery = KeywordExtractor.toTsQuery(keywords);
        return new KeywordSearchResult(keywords,
                chunkHits(projectId, tsQuery, keywords, max),
                summaryHits(projectId, tsQuery, max),
                nameHits(projectId, keywords, max));
    }

    // ---- 1. code chunks (full-text) ----

    private List<ChunkHit> chunkHits(Long projectId, String tsQuery, List<String> keywords, int limit) {
        List<ChunkHit> hits = new ArrayList<>();
        for (Object r : run(FtsSql.CHUNK_QUERY, projectId, tsQuery, limit)) {
            Object[] row = (Object[]) r;
            hits.add(new ChunkHit(((Number) row[0]).longValue(), (String) row[1],
                    ((Number) row[2]).intValue(), ((Number) row[3]).intValue(),
                    ((Number) row[6]).doubleValue(), (String) row[5], preview((String) row[4], keywords)));
        }
        return hits;
    }

    // ---- 2. file summaries (full-text) ----

    private List<SummaryHit> summaryHits(Long projectId, String tsQuery, int limit) {
        List<SummaryHit> hits = new ArrayList<>();
        for (Object r : run(FtsSql.SUMMARY_QUERY, projectId, tsQuery, limit)) {
            Object[] row = (Object[]) r;
            hits.add(new SummaryHit((String) row[0], ((Number) row[2]).doubleValue(), (String) row[1]));
        }
        return hits;
    }

    private List<?> run(String sql, Long projectId, String tsQuery, int limit) {
        return entityManager.createNativeQuery(sql)
                .setParameter("query", tsQuery)
                .setParameter("projectId", projectId)
                .setParameter("limit", limit)
                .getResultList();
    }

    // ---- 3. file names, paths and symbol names (plain Java scoring) ----

    private List<NameHit> nameHits(Long projectId, List<String> keywords, int limit) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, List<String>> reasons = new HashMap<>();

        for (String path : fileRepository.findAllPaths(projectId)) {
            String lower = path.toLowerCase(Locale.ROOT);
            String fileName = lower.substring(lower.lastIndexOf('/') + 1);
            for (String kw : keywords) {
                if (fileName.contains(kw)) {
                    add(scores, reasons, path, 3.0, "file name contains '" + kw + "'");
                } else if (lower.contains(kw)) {
                    add(scores, reasons, path, 1.5, "path contains '" + kw + "'");
                }
            }
        }
        for (String kw : keywords) {
            for (CodeSymbol s : symbolRepository.searchByName(projectId, kw, PageRequest.of(0, 200))) {
                double weight = s.getName().equalsIgnoreCase(kw) ? 3.0 : 1.0;   // exact name beats partial
                add(scores, reasons, s.getFile().getRelativePath(), weight, "symbol " + s.getQualifiedName());
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(e -> new NameHit(e.getKey(), e.getValue(), reasons.get(e.getKey())))
                .toList();
    }

    private static void add(Map<String, Double> scores, Map<String, List<String>> reasons,
                            String path, double weight, String reason) {
        scores.merge(path, weight, Double::sum);
        List<String> list = reasons.computeIfAbsent(path, k -> new ArrayList<>());
        if (list.size() < 5 && !list.contains(reason)) {
            list.add(reason);
        }
    }

    /** A few lines around the first line that contains a keyword, so a result shows WHY it matched. */
    static String preview(String content, List<String> keywords) {
        List<String> lines = content.lines().toList();
        int hit = 0;
        search:
        for (int i = 0; i < lines.size(); i++) {
            String lower = lines.get(i).toLowerCase(Locale.ROOT);
            for (String kw : keywords) {
                if (lower.contains(kw)) {
                    hit = i;
                    break search;
                }
            }
        }
        int from = Math.max(0, hit - 1);
        int to = Math.min(lines.size(), hit + 3);
        return Text.truncate(String.join("\n", lines.subList(from, to)), 400);
    }
}