package com.contextlayer.backend.indexing;

import java.util.List;
import java.util.Map;

public record ScanPreview(
        int fileCount,
        long totalBytes,
        Map<String, Long> filesByLanguage,
        Map<String, Integer> skipped,
        List<String> sample) {
}