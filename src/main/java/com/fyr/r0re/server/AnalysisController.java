package com.fyr.r0re.server;

import com.fyr.r0re.protocol.Models.CreateRuntimeEventRequest;
import com.fyr.r0re.protocol.Models.RuntimeEvent;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalysisController {
    private final R0reService service;
    private final AnalysisArtifactsService artifacts;

    public AnalysisController(R0reService service, AnalysisArtifactsService artifacts) {
        this.service = service;
        this.artifacts = artifacts;
    }

    @GetMapping("/projects/{projectId}/analysis/trace")
    public Map<String, Object> trace(
            @PathVariable("projectId") String projectId,
            @RequestParam(name = "limit", defaultValue = "3") int limit) {
        service.getProject(projectId);
        return artifacts.loadTraceView(projectId, limit);
    }

    @GetMapping("/projects/{projectId}/analysis/document")
    public Map<String, Object> document(@PathVariable("projectId") String projectId) {
        service.getProject(projectId);
        return artifacts.loadDocumentView(projectId);
    }

    @GetMapping("/projects/{projectId}/runtime")
    public Object runtime(@PathVariable("projectId") String projectId,
                          @RequestParam(name = "event_limit", defaultValue = "20") int eventLimit) {
        return service.projectRuntime(projectId, eventLimit);
    }

    @GetMapping("/projects/{projectId}/verification-state")
    public Object verificationState(@PathVariable("projectId") String projectId) {
        return service.projectVerificationState(projectId);
    }

    @GetMapping("/projects/{projectId}/verification-intents")
    public Object verificationIntents(@PathVariable("projectId") String projectId) {
        return Map.of(
                "project_id", projectId,
                "verification_intents", service.projectVerificationIntents(projectId)
        );
    }

    @GetMapping("/projects/{projectId}/events")
    public Object events(@PathVariable("projectId") String projectId,
                         @RequestParam(name = "limit", defaultValue = "100") int limit) {
        service.getProject(projectId);
        var events = service.runtimeEvents(projectId, limit);
        return Map.of(
                "project_id", projectId,
                "events", events,
                "event_count", events.size()
        );
    }

    @GetMapping("/projects/{projectId}/artifacts")
    public Object artifacts(@PathVariable("projectId") String projectId,
                            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        service.getProject(projectId);
        return artifacts.loadArtifactsView(projectId, limit);
    }

    @GetMapping("/projects/{projectId}/artifact-index")
    public Object artifactIndex(@PathVariable("projectId") String projectId,
                                @RequestParam(name = "per_group_limit", defaultValue = "10") int perGroupLimit) {
        service.getProject(projectId);
        return artifacts.loadArtifactIndexView(projectId, perGroupLimit);
    }

    @GetMapping("/projects/{projectId}/jadx-search")
    public Object jadxSearch(@PathVariable("projectId") String projectId,
                             @RequestParam(name = "query") String query,
                             @RequestParam(name = "limit", defaultValue = "20") int limit) {
        service.getProject(projectId);
        return artifacts.searchJadxView(projectId, query, limit);
    }

    @GetMapping("/projects/{projectId}/jni-method-map")
    public Object jniMethodMap(@PathVariable("projectId") String projectId,
                               @RequestParam(name = "limit", defaultValue = "100") int limit) {
        service.getProject(projectId);
        return artifacts.loadJniMethodMapView(projectId, limit);
    }

    @PostMapping("/projects/{projectId}/runtime/events")
    public RuntimeEvent appendRuntimeEvent(@PathVariable("projectId") String projectId,
                                           @RequestBody CreateRuntimeEventRequest body) {
        return service.appendRuntimeEvent(projectId, body);
    }

    @PostMapping("/projects/{projectId}/analysis/snapshot")
    public Map<String, Object> snapshot(@PathVariable("projectId") String projectId) {
        service.getProject(projectId);
        return artifacts.snapshotWorkspace(projectId);
    }
}
