package com.fyr.r0re.server;

import com.fyr.r0re.protocol.Models.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProjectsController {
    private final R0reService service;

    public ProjectsController(R0reService service) {
        this.service = service;
    }

    @GetMapping("/projects")
    public List<ProjectSummary> listProjects() {
        return service.listProjects();
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectDetail createProject(@Valid @RequestBody CreateProjectRequest body) {
        return service.createProject(body);
    }

    @GetMapping("/projects/{projectId}")
    public ProjectDetail getProject(@PathVariable("projectId") String projectId) {
        return service.getProject(projectId);
    }

    @DeleteMapping("/projects/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@PathVariable("projectId") String projectId) {
        service.deleteProject(projectId);
    }

    @PutMapping("/projects/{projectId}/title")
    public ProjectMeta updateTitle(@PathVariable("projectId") String projectId, @Valid @RequestBody UpdateProjectTitleRequest body) {
        return service.updateTitle(projectId, body);
    }

    @PutMapping("/projects/{projectId}/status")
    public ProjectMeta updateStatus(@PathVariable("projectId") String projectId, @Valid @RequestBody UpdateProjectStatusRequest body) {
        return service.updateStatus(projectId, body);
    }

    @PostMapping("/projects/{projectId}/reason/claim")
    public ProjectMeta claimReason(@PathVariable("projectId") String projectId, @Valid @RequestBody ReasonClaimRequest body) {
        return service.claimReason(projectId, body);
    }

    @PostMapping("/projects/{projectId}/reason/heartbeat")
    public ProjectMeta reasonHeartbeat(@PathVariable("projectId") String projectId, @Valid @RequestBody HeartbeatRequest body) {
        return service.reasonHeartbeat(projectId, body);
    }

    @PostMapping("/projects/{projectId}/reason/release")
    public ProjectMeta releaseReason(@PathVariable("projectId") String projectId, @Valid @RequestBody HeartbeatRequest body) {
        return service.releaseReason(projectId, body);
    }

    @PostMapping("/projects/{projectId}/complete")
    public CompletionCandidate complete(@PathVariable("projectId") String projectId, @Valid @RequestBody CompleteRequest body) {
        return service.complete(projectId, body);
    }

    @GetMapping("/projects/{projectId}/completion-candidates")
    public List<CompletionCandidate> completionCandidates(@PathVariable("projectId") String projectId) {
        return service.completionCandidates(projectId);
    }

    @PostMapping("/projects/{projectId}/completion-candidates/{candidateId}/approve")
    public CompletionCandidate approveCompletionCandidate(@PathVariable("projectId") String projectId, @PathVariable("candidateId") String candidateId) {
        return service.approveCompletionCandidate(projectId, candidateId);
    }

    @PostMapping("/projects/{projectId}/completion-candidates/{candidateId}/start-verification")
    public CompletionCandidate startCompletionVerification(@PathVariable("projectId") String projectId, @PathVariable("candidateId") String candidateId, @Valid @RequestBody VerifyCompletionRequest body) {
        return service.startCompletionVerification(projectId, candidateId, body);
    }

    @PostMapping("/projects/{projectId}/re-run-verifier")
    public CompletionCandidate rerunVerifier(@PathVariable("projectId") String projectId, @Valid @RequestBody RerunVerifierRequest body) {
        return service.rerunVerifier(projectId, body);
    }

    @PostMapping("/projects/{projectId}/completion-candidates/{candidateId}/fail-verification")
    public CompletionCandidate failVerification(@PathVariable("projectId") String projectId, @PathVariable("candidateId") String candidateId, @Valid @RequestBody VerificationFailureRequest body) {
        return service.failCompletionVerification(projectId, candidateId, body);
    }

    @PostMapping("/projects/{projectId}/completion-candidates/{candidateId}/reject")
    public CompletionCandidate rejectCompletionCandidate(@PathVariable("projectId") String projectId, @PathVariable("candidateId") String candidateId) {
        return service.rejectCompletionCandidate(projectId, candidateId);
    }

    @PostMapping("/projects/{projectId}/completion-candidates/{candidateId}/finalize")
    public Intent finalizeCompletion(@PathVariable("projectId") String projectId, @PathVariable("candidateId") String candidateId, @Valid @RequestBody FinalizeCompletionRequest body) {
        return service.finalizeCompletion(projectId, candidateId, body);
    }

    @GetMapping("/projects/{projectId}/health")
    public ProjectHealth projectHealth(@PathVariable("projectId") String projectId) {
        return service.projectHealth(projectId);
    }

    @PostMapping("/projects/{projectId}/quarantine")
    public ProjectHealth quarantineProject(@PathVariable("projectId") String projectId, @Valid @RequestBody QuarantineRequest body) {
        return service.quarantineProject(projectId, body);
    }

    @PostMapping("/projects/{projectId}/unquarantine")
    public ProjectHealth unquarantineProject(@PathVariable("projectId") String projectId, @Valid @RequestBody QuarantineRequest body) {
        return service.unquarantineProject(projectId, body);
    }

    @PostMapping("/projects/{projectId}/repair-completion")
    public RepairCompletionResponse repairCompletion(@PathVariable("projectId") String projectId, @Valid @RequestBody RepairCompletionRequest body) {
        return service.repairCompletion(projectId, body);
    }

    @PostMapping("/projects/{projectId}/resume-execution")
    public ProjectMeta resumeExecution(@PathVariable("projectId") String projectId, @Valid @RequestBody ResumeExecutionRequest body) {
        return service.resumeExecution(projectId, body);
    }

    @PostMapping("/projects/{projectId}/reopen")
    public ReopenResponse reopen(@PathVariable("projectId") String projectId, @Valid @RequestBody ReopenRequest body) {
        return service.reopen(projectId, body);
    }
}
