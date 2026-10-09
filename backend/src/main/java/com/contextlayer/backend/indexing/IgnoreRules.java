package com.contextlayer.backend.indexing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.PatternSyntaxException;

/** Simplified .gitignore matcher. No negation, root-level .gitignore only. */
public final class IgnoreRules {

    private record Rule(List<PathMatcher> self, List<PathMatcher> beneath, boolean dirOnly) {
    }

    private final List<Rule> rules;

    private IgnoreRules(List<Rule> rules) {
        this.rules = rules;
    }

    public static IgnoreRules none() {
        return new IgnoreRules(List.of());
    }

    public static IgnoreRules fromGitignore(Path root) {
        Path file = root.resolve(".gitignore");
        if (!Files.isRegularFile(file)) {
            return none();
        }
        List<Rule> parsed = new ArrayList<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                parse(raw).ifPresent(parsed::add);
            }
        } catch (IOException e) {
            // unreadable or non-UTF-8 .gitignore: fall back to whatever we parsed so far
        }
        return new IgnoreRules(parsed);
    }

    /** @param relative path relative to the project root */
    public boolean isIgnored(Path relative, boolean isDirectory) {
        for (Rule rule : rules) {
            if ((isDirectory || !rule.dirOnly()) && matchesAny(rule.self(), relative)) {
                return true;
            }
            if (matchesAny(rule.beneath(), relative)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(List<PathMatcher> matchers, Path path) {
        for (PathMatcher m : matchers) {
            if (m.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<Rule> parse(String raw) {
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
            return Optional.empty();
        }
        boolean dirOnly = line.endsWith("/");
        if (dirOnly) {
            line = line.substring(0, line.length() - 1);
        }
        boolean anchored = line.contains("/");   // a slash anywhere means "relative to root"
        if (line.startsWith("/")) {
            line = line.substring(1);
        }
        if (line.isEmpty()) {
            return Optional.empty();
        }

        // Java's glob "**/x" does NOT match a top-level "x", so unanchored rules need both forms.
        List<String> selfGlobs = anchored ? List.of(line) : List.of(line, "**/" + line);
        List<String> beneathGlobs = anchored
                ? List.of(line + "/**")
                : List.of(line + "/**", "**/" + line + "/**");
        try {
            return Optional.of(new Rule(compile(selfGlobs), compile(beneathGlobs), dirOnly));
        } catch (PatternSyntaxException e) {
            return Optional.empty();
        }
    }

    private static List<PathMatcher> compile(List<String> globs) {
        List<PathMatcher> result = new ArrayList<>();
        for (String g : globs) {
            result.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
        }
        return result;
    }
}
