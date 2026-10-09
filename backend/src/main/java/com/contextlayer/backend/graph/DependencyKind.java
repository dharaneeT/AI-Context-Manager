package com.contextlayer.backend.graph;

public enum DependencyKind {
	IMPORT, // explicit import statement
	REFERENCE // uses another type without importing it (same package). Added on Day 8.
}
