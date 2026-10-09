package com.contextlayer.backend.search;

import com.contextlayer.backend.search.SearchResults.KeywordSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class SearchController {

    private final KeywordSearchService searchService;

    @GetMapping("/search")
    public KeywordSearchResult search(@PathVariable Long projectId, @RequestParam String q,
                                      @RequestParam(defaultValue = "10") int limit) {
        return searchService.search(projectId, q, limit);
    }
}