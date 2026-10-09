package com.contextlayer.backend.graph;

public record GraphEdge(String from, String to, DependencyKind kind) {}
