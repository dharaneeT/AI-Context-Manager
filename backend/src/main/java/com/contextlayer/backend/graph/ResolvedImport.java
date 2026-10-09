package com.contextlayer.backend.graph;

import java.util.List;

/** @param targets project files this import points to. Empty means external or unresolvable. */
public record ResolvedImport(String specifier, List<String> targets) {
}