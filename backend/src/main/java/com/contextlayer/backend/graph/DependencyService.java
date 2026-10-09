package com.contextlayer.backend.graph;

import com.contextlayer.backend.common.NotFoundException;
import com.contextlayer.backend.indexing.ProjectFile;
import com.contextlayer.backend.indexing.ProjectFileRepository;
import com.contextlayer.backend.project.Project;
import com.contextlayer.backend.project.ProjectRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class DependencyService {

    private final ProjectRepository projectRepository;
    private final ProjectFileRepository fileRepository;
    private final FileDependencyRepository dependencyRepository;
    private final Map<String, ImportStrategy> strategies = new HashMap<>();

    public DependencyService(ProjectRepository projectRepository, ProjectFileRepository fileRepository,
                             FileDependencyRepository dependencyRepository, List<ImportStrategy> all) {
        this.projectRepository = projectRepository;
        this.fileRepository = fileRepository;
        this.dependencyRepository = dependencyRepository;
        for (ImportStrategy strategy : all) {
            for (String language : strategy.languages()) {
                strategies.put(language, strategy);
            }
        }
    }

    /** Rebuilds the whole graph. It's pure regex work, so it's fast and always consistent. */
    @Transactional
    public GraphBuildResult build(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
        Path root = Path.of(project.getRootPath());

        Map<String, ProjectFile> byPath = new TreeMap<>();
        fileRepository.findAllByProjectId(projectId).forEach(f -> byPath.put(f.getRelativePath(), f));
        ProjectIndex index = ProjectIndex.build(root, byPath);

        dependencyRepository.deleteByProjectId(projectId);

        List<FileDependency> edges = new ArrayList<>();
        int analyzed = 0;
        int resolved = 0;
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

            Set<String> seen = new HashSet<>();   // de-duplicate repeated imports
            for (ResolvedImport imp : strategy.analyze(file.getRelativePath(), content, index)) {
                if (imp.targets().isEmpty()) {
                    if (seen.add(imp.specifier() + "|")) {
                        edges.add(edge(file, null, imp.specifier()));
                        external++;
                    }
                    continue;
                }
                for (String target : imp.targets()) {
                    if (!target.equals(file.getRelativePath()) && seen.add(imp.specifier() + "|" + target)) {
                        edges.add(edge(file, byPath.get(target), imp.specifier()));
                        resolved++;
                    }
                }
            }
        }
        dependencyRepository.saveAll(edges);
        log.info("Graph for project {}: {} files, {} edges, {} external", projectId, analyzed, resolved, external);
        return new GraphBuildResult(analyzed, resolved, 0, external);
    }

    private static FileDependency edge(ProjectFile from, ProjectFile to, String specifier) {
        FileDependency d = new FileDependency();
        d.setFromFile(from);
        d.setToFile(to);
        d.setSpecifier(specifier.length() > 500 ? specifier.substring(0, 500) : specifier);
        d.setKind(DependencyKind.IMPORT);
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