package com.contextlayer.backend.graph;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/graph")
@RequiredArgsConstructor
public class GraphController {

    private final DependencyService service;

    @PostMapping("/build")
    public GraphBuildResult build(@PathVariable Long projectId) {
        return service.build(projectId);
    }

    @GetMapping("/dependencies")
    public List<String> dependencies(@PathVariable Long projectId, @RequestParam String path) {
        return service.dependenciesOf(projectId, path);
    }

    @GetMapping("/dependents")
    public List<String> dependents(@PathVariable Long projectId, @RequestParam String path) {
        return service.dependentsOf(projectId, path);
    }

    @GetMapping("/neighborhood")
    public Map<String, Integer> neighborhood(@PathVariable Long projectId, @RequestParam String path,
                                             @RequestParam(defaultValue = "2") int depth) {
        return service.loadGraph(projectId).neighborhood(path, Math.min(Math.max(depth, 1), 4));
    }

    @GetMapping("/hubs")
    public List<FileDegree> hubs(@PathVariable Long projectId, @RequestParam(defaultValue = "10") int limit) {
        return service.mostDependedOn(projectId, limit);
    }

    @GetMapping("/external")
    public List<ExternalImport> external(@PathVariable Long projectId,
                                         @RequestParam(defaultValue = "20") int limit) {
        return service.topExternal(projectId, limit);
    }
}