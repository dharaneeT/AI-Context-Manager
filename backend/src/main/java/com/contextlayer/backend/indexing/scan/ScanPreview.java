package com.contextlayer.backend.indexing.scan;

import java.util.List;
import java.util.Map;

//contains a summary of the scan, including file count, total bytes, skipped reasons and a sample of relative paths.
public record ScanPreview(
        int fileCount,
        long totalBytes,
        Map<String, Long> filesByLanguage,
        Map<String, Integer> skipped,
        List<String> sample) {
}