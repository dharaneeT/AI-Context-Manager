package com.contextlayer.backend.graph;

public record GraphBuildResult(int filesAnalyzed, int resolvedEdges, int referenceEdges, int externalImports) {}
