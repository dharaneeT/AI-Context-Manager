package com.contextlayer.backend.indexing.scan;

import java.util.List;
import java.util.Map;

/** @param skipped reason -> how many files or directories were skipped for that reason */
public record ScanResult(List<ScannedFile> files, Map<String, Integer> skipped) {}
