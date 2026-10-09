package com.contextlayer.backend.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class JavaImportStrategy implements ImportStrategy {

    // import [static] a.b.C[.*] [as X] ;   (semicolon is optional, which covers Kotlin)
    private static final Pattern IMPORT = Pattern.compile(
            "^\\s*import\\s+(static\\s+)?([\\w.]+?)(\\.\\*)?(?:\\s+as\\s+\\w+)?\\s*(?:;|$)", Pattern.MULTILINE);
    private static final int WILDCARD_CAP = 25;

    @Override
    public Set<String> languages() {
        return Set.of("java", "kotlin");
    }

    @Override
    public List<ResolvedImport> analyze(String filePath, String content, ProjectIndex index) {
        List<ResolvedImport> result = new ArrayList<>();
        Matcher m = IMPORT.matcher(content);
        while (m.find()) {
            boolean isStatic = m.group(1) != null;
            boolean wildcard = m.group(3) != null;
            String name = m.group(2);

            List<String> targets = new ArrayList<>();
            if (wildcard) {
                String type = resolveType(name, index);          // "import a.Outer.*" or "import static a.Foo.*"
                if (type != null) {
                    targets.add(type);
                } else if (!isStatic) {                           // "import a.b.*": every file in package a.b
                    List<String> inPackage = index.javaPackageToPaths().getOrDefault(name, List.of());
                    targets.addAll(inPackage.subList(0, Math.min(WILDCARD_CAP, inPackage.size())));
                }
            } else {
                String qualified = isStatic ? parent(name) : name;   // static member -> its owning class
                String type = resolveType(qualified, index);
                if (type != null) {
                    targets.add(type);
                }
            }
            result.add(new ResolvedImport(wildcard ? name + ".*" : name, targets));
        }
        return result;
    }

    /** Tries "a.b.Outer.Inner", then "a.b.Outer", then "a.b"... until a known type matches. */
    private static String resolveType(String name, ProjectIndex index) {
        String candidate = name;
        while (!candidate.isEmpty()) {
            String path = index.javaTypeToPath().get(candidate);
            if (path != null) {
                return path;
            }
            int dot = candidate.lastIndexOf('.');
            if (dot < 0) {
                break;
            }
            candidate = candidate.substring(0, dot);
        }
        return null;
    }

    private static String parent(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}