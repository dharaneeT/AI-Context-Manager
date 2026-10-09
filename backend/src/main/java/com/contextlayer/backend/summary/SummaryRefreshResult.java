package com.contextlayer.backend.summary;

public record SummaryRefreshResult(int generated, int unchanged, String generator) {}
