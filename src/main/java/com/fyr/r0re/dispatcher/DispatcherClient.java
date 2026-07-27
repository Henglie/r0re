package com.fyr.r0re.dispatcher;

import com.fyr.r0re.protocol.Models.*;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

public class DispatcherClient {
    private final RestClient rest;

    public record ApiResult(boolean ok, int statusCode, String responseText) {
    }

    public DispatcherClient(String server) {
        this.rest = RestClient.builder().baseUrl(server).build();
    }

    public List<ProjectSummary> projects() {
        ProjectSummary[] data = rest.get().uri("/projects").retrieve().body(ProjectSummary[].class);
        return data == null ? List.of() : List.of(data);
    }

    public ProjectDetail project(String id) {
        return rest.get().uri("/projects/{id}", id).retrieve().body(ProjectDetail.class);
    }

    public ProjectRuntime projectRuntime(String id) {
        return rest.get().uri("/projects/{id}/runtime", id).retrieve().body(ProjectRuntime.class);
    }

    public ProjectHealth projectHealth(String id) {
        return rest.get().uri("/projects/{id}/health", id).retrieve().body(ProjectHealth.class);
    }

    public void resumeExecution(String projectId, String creator) {
        rest.post()
                .uri("/projects/{id}/resume-execution", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ResumeExecutionRequest(creator))
                .retrieve()
                .body(ProjectMeta.class);
    }

    public List<CompletionCandidate> completionCandidates(String projectId) {
        CompletionCandidate[] data = rest.get()
                .uri("/projects/{id}/completion-candidates", projectId)
                .retrieve()
                .body(CompletionCandidate[].class);
        return data == null ? List.of() : List.of(data);
    }

    public Intent createIntent(String projectId, List<String> from, String description, String creator) {
        return createIntent(projectId, from, description, creator, null);
    }

    public Intent createIntent(String projectId, List<String> from, String description, String creator, String worker) {
        return rest.post()
                .uri("/projects/{id}/intents", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateIntentPayload(from, description, creator, worker))
                .retrieve()
                .body(Intent.class);
    }

    public Fact blackboardAddFact(String projectId, String worker, String description, boolean verified) {
        return rest.post()
                .uri("/projects/{id}/blackboard/facts", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new BlackboardFactRequest(worker, description, verified, null))
                .retrieve()
                .body(Fact.class);
    }

    public Intent heartbeat(String projectId, String intentId, String worker) {
        return heartbeat(projectId, intentId, new HeartbeatRequest(worker, null, null, null, null, null, null, null));
    }

    public Intent heartbeat(String projectId, String intentId, HeartbeatRequest body) {
        return rest.post()
                .uri("/projects/{projectId}/intents/{intentId}/heartbeat", projectId, intentId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Intent.class);
    }

    public ApiResult heartbeatResult(String projectId, String intentId, String worker) {
        return heartbeatResult(projectId, intentId, new HeartbeatRequest(worker, null, null, null, null, null, null, null));
    }

    public ApiResult heartbeatResult(String projectId, String intentId, HeartbeatRequest body) {
        return requestResult(() -> rest.post()
                .uri("/projects/{projectId}/intents/{intentId}/heartbeat", projectId, intentId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity());
    }

    public ConcludeResponse conclude(String projectId, String intentId, String worker, String description) {
        return rest.post()
                .uri("/projects/{projectId}/intents/{intentId}/conclude", projectId, intentId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("worker", worker, "description", description))
                .retrieve()
                .body(ConcludeResponse.class);
    }

    public CompletionCandidate complete(String projectId, List<String> from, String description, String worker) {
        return rest.post()
                .uri("/projects/{id}/complete", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("from", from, "description", description, "worker", worker))
                .retrieve()
                .body(CompletionCandidate.class);
    }

    public CompletionCandidate startCompletionVerification(String projectId, String candidateId, String worker, String promptGroup, String tool) {
        return rest.post()
                .uri("/projects/{projectId}/completion-candidates/{candidateId}/start-verification", projectId, candidateId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("worker", worker, "prompt_group", promptGroup, "tool", tool))
                .retrieve()
                .body(CompletionCandidate.class);
    }

    public CompletionCandidate approveCompletionCandidate(String projectId, String candidateId) {
        return rest.post()
                .uri("/projects/{projectId}/completion-candidates/{candidateId}/approve", projectId, candidateId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .body(CompletionCandidate.class);
    }

    public CompletionCandidate failCompletionVerification(String projectId, String candidateId, String worker, String message, boolean retryable) {
        return rest.post()
                .uri("/projects/{projectId}/completion-candidates/{candidateId}/fail-verification", projectId, candidateId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("worker", worker, "message", message, "retryable", retryable))
                .retrieve()
                .body(CompletionCandidate.class);
    }

    public CompletionCandidate rejectCompletionCandidate(String projectId, String candidateId) {
        return rest.post()
                .uri("/projects/{projectId}/completion-candidates/{candidateId}/reject", projectId, candidateId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .body(CompletionCandidate.class);
    }

    public Intent finalizeCompletion(String projectId, String candidateId, String worker) {
        return rest.post()
                .uri("/projects/{projectId}/completion-candidates/{candidateId}/finalize", projectId, candidateId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("worker", worker))
                .retrieve()
                .body(Intent.class);
    }

    public ProjectHealth quarantineProject(String projectId, String reason, String worker) {
        return rest.post()
                .uri("/projects/{projectId}/quarantine", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", reason, "worker", worker))
                .retrieve()
                .body(ProjectHealth.class);
    }

    public ApiResult reasonHeartbeatResult(String projectId, String worker) {
        return reasonHeartbeatResult(projectId, new HeartbeatRequest(worker, null, null, null, null, null, null, null));
    }

    public ApiResult reasonHeartbeatResult(String projectId, HeartbeatRequest body) {
        return requestResult(() -> rest.post()
                .uri("/projects/{projectId}/reason/heartbeat", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity());
    }

    public ApiResult release(String projectId, String intentId, String worker) {
        return requestResult(() -> rest.post()
                .uri("/projects/{projectId}/intents/{intentId}/release", projectId, intentId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("worker", worker))
                .retrieve()
                .toBodilessEntity());
    }

    public ApiResult claimReason(String projectId, String worker, String trigger) {
        return requestResult(() -> rest.post()
                .uri("/projects/{projectId}/reason/claim", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("worker", worker, "trigger", trigger))
                .retrieve()
                .toBodilessEntity());
    }

    public ApiResult releaseReason(String projectId, String worker) {
        return requestResult(() -> rest.post()
                .uri("/projects/{projectId}/reason/release", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("worker", worker))
                .retrieve()
                .toBodilessEntity());
    }

    public Settings settings() {
        return rest.get().uri("/settings").retrieve().body(Settings.class);
    }

    public ProjectMeta updateProjectStatus(String projectId, String status) {
        return rest.put()
                .uri("/projects/{id}/status", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", status))
                .retrieve()
                .body(ProjectMeta.class);
    }

    public Hint createHint(String projectId, String content, String creator) {
        return rest.post()
                .uri("/projects/{projectId}/hints", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateHintRequest(content, creator))
                .retrieve()
                .body(Hint.class);
    }

    public String export(String projectId, String format) {
        return rest.get()
                .uri("/projects/{id}/export?format={format}", projectId, format)
                .retrieve()
                .body(String.class);
    }

    public RuntimeEvent appendRuntimeEvent(String projectId, CreateRuntimeEventRequest body) {
        return rest.post()
                .uri("/projects/{projectId}/runtime/events", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(RuntimeEvent.class);
    }

    private record CreateIntentPayload(List<String> from, String description, String creator, String worker) {
    }

    private ApiResult requestResult(RequestCall call) {
        try {
            ResponseEntity<Void> response = call.execute();
            return new ApiResult(response.getStatusCode().is2xxSuccessful(), response.getStatusCode().value(), "");
        } catch (RestClientResponseException exc) {
            return new ApiResult(false, exc.getStatusCode().value(), exc.getResponseBodyAsString());
        } catch (RuntimeException exc) {
            return new ApiResult(false, 0, exc.toString());
        }
    }

    @FunctionalInterface
    private interface RequestCall {
        ResponseEntity<Void> execute();
    }
}
