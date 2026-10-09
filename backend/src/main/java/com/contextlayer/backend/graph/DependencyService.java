package com.contextlayer.backend.graph;

import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.indexing.ProjectFile;
import com.contextlayer.backend.indexing.ProjectFileRepository;
import com.contextlayer.backend.project.Project;
import com.contextlayer.backend.project.ProjectRepository;
import com.contextlayer.backend.symbols.CodeSymbol;
import com.contextlayer.backend.symbols.CodeSymbolRepository;
import com.contextlayer.backend.symbols.SymbolKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class DependencyService {

    private static final Set<String> REFERENCE_LANGUAGES = Set.of("java", "kotlin");
    private static final Pattern TYPE_NAME = Pattern.compile("\\b[A-Z][A-Za-z0-9_]{2,}\\b");

    private final ProjectRepository projectRepository;
    private final ProjectFileRepository fileRepository;
    private final FileDependencyRepository dependencyRepository;
    private final CodeSymbolRepository symbolRepository;
    private final Map<String, ImportStrategy> strategies = new HashMap<>();

    public DependencyService(ProjectRepository projectRepository, ProjectFileRepository fileRepository,
                             FileDependencyRepository dependencyRepository, CodeSymbolRepository symbolRepository,
                             List<ImportStrategy> all) {
        this.projectRepository = projectRepository;
        this.fileRepository = fileRepository;
        this.dependencyRepository = dependencyRepository;
        this.symbolRepository = symbolRepository;
        for (ImportStrategy strategy : all) {
            for (String language : strategy.languages()) {
                strategies.put(language, strategy);
            }
        }
    }

    @Transactional
    public GraphBuildResult build(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
        Path root = Path.of(project.getRootPath());

        Map<String, ProjectFile> byPath = new TreeMap<>();
        fileRepository.findAllByProjectId(projectId).forEach(f -> byPath.put(f.getRelativePath(), f));
        ProjectIndex index = ProjectIndex.build(root, byPath);

        // type name -> files declaring it (only unambiguous names, i.e. exactly one file, are used for references)
        Map<String, List<String>> typeToPaths = symbolRepository
                .findTopLevelTypes(projectId, EnumSet.of(SymbolKind.CLASS, SymbolKind.INTERFACE,
                        SymbolKind.ENUM, SymbolKind.RECORD))
                .stream()
                .filter(s -> s.getName().length() >= 3)
                .collect(Collectors.groupingBy(CodeSymbol::getName,
                        Collectors.mapping(s -> s.getFile().getRelativePath(), Collectors.toList())));

        dependencyRepository.deleteByProjectId(projectId);

        List<FileDependency> edges = new ArrayList<>();
        int analyzed = 0;
        int resolved = 0;
        int references = 0;
        int external = 0;

        for (ProjectFile file : byPath.values()) {
            ImportStrategy strategy = strategies.get(file.getLanguage());
            if (strategy == null) {
                continue;
            }
            String content;
            try {
                content = new String(Files.readAllBytes(root.resolve(file.getRelativePath())), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Cannot read {} for graph build: {}", file.getRelativePath(), e.getMessage());
                continue;
            }
            analyzed++;

            Set<String> seen = new HashSet<>();
            Set<String> importedTargets = new HashSet<>();
            for (ResolvedImport imp : strategy.analyze(file.getRelativePath(), content, index)) {
                if (imp.targets().isEmpty()) {
                    if (seen.add(imp.specifier() + "|")) {
                        edges.add(edge(file, null, imp.specifier(), DependencyKind.IMPORT));
                        external++;
                    }
                    continue;
                }
                for (String target : imp.targets()) {
                    if (!target.equals(file.getRelativePath()) && seen.add(imp.specifier() + "|" + target)) {
                        edges.add(edge(file, byPath.get(target), imp.specifier(), DependencyKind.IMPORT));
                        importedTargets.add(target);
                        resolved++;
                    }
                }
            }

            if (REFERENCE_LANGUAGES.contains(file.getLanguage())) {
                Set<String> tokens = new HashSet<>();
                Matcher m = TYPE_NAME.matcher(content);
                while (m.find()) {
                    String token = m.group();
                    if (!tokens.add(token)) {
                        continue;
                    }
                    List<String> targets = typeToPaths.get(token);
                    if (targets == null || targets.size() != 1) {
                        continue;                                  // unknown or ambiguous name
                    }
                    String target = targets.get(0);
                    if (target.equals(file.getRelativePath()) || importedTargets.contains(target)
                            || !REFERENCE_LANGUAGES.contains(byPath.get(target).getLanguage())) {
                        continue;                                  // self, already imported, or other language
                    }
                    edges.add(edge(file, byPath.get(target), token, DependencyKind.REFERENCE));
                    references++;
                }
            }
        }
        dependencyRepository.saveAll(edges);
        log.info("Graph for project {}: {} files, {} imports, {} references, {} external",
                projectId, analyzed, resolved, references, external);
        return new GraphBuildResult(analyzed, resolved, references, external);
    }

    private static FileDependency edge(ProjectFile from, ProjectFile to, String specifier, DependencyKind kind) {
        FileDependency d = new FileDependency();
        d.setFromFile(from);
        d.setToFile(to);
        d.setSpecifier(specifier.length() > 500 ? specifier.substring(0, 500) : specifier);
        d.setKind(kind);
        return d;
    }

    @Transactional(readOnly = true)
    public DependencyGraph loadGraph(Long projectId) {
        return new DependencyGraph(dependencyRepository.findResolvedEdges(projectId));
    }

    @Transactional(readOnly = true)
    public List<String> dependenciesOf(Long projectId, String path) {
        return dependencyRepository.findDependencies(projectId, path);
    }

    @Transactional(readOnly = true)
    public List<String> dependentsOf(Long projectId, String path) {
        return dependencyRepository.findDependents(projectId, path);
    }

    @Transactional(readOnly = true)
    public List<FileDegree> mostDependedOn(Long projectId, int limit) {
        return dependencyRepository.mostDependedOn(projectId, PageRequest.of(0, Math.min(Math.max(limit, 1), 100)));
    }

    @Transactional(readOnly = true)
    public List<ExternalImport> topExternal(Long projectId, int limit) {
        return dependencyRepository.topExternal(projectId, PageRequest.of(0, Math.min(Math.max(limit, 1), 100)));
    }
}