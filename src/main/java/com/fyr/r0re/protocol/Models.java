package com.fyr.r0re.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class Models {
    private Models() {
    }

    public record Settings(int intent_timeout, int reason_timeout, boolean workspace_sync_enabled) {
    }

    public record Fact(String id, String description, String kind, Boolean verified, String creator) {
        public Fact(String id, String description) {
            this(id, description, "fact", false, null);
        }
    }

    public record Intent(
            String id,
            @JsonProperty("from") List<String> from,
            String to,
            String description,
            String creator,
            String worker,
            String last_heartbeat_at,
            String created_at,
            String concluded_at
    ) {
    }

    public record Hint(String id, String content, String creator, String created_at) {
    }

    public record RuntimeEvent(
            long id,
            String project_id,
            String scope_type,
            String scope_id,
            String worker,
            String event_type,
            String phase,
            String step,
            String message,
            String artifact,
            String tool,
            Integer progress_current,
            Integer progress_total,
            String created_at
    ) {
    }

    public record RuntimeScope(
            String scope_type,
            String scope_id,
            String worker,
            String phase,
            String step,
            String message,
            String artifact,
            String tool,
            Integer progress_current,
            Integer progress_total,
            String last_heartbeat_at,
            String latest_event_at,
            String latest_event_type,
            boolean stalled
    ) {
    }

    public record ProjectRuntime(
            String schema_version,
            ProjectMeta project,
            List<RuntimeScope> intents,
            List<RuntimeScope> verifications,
            RuntimeScope reason,
            List<RuntimeEvent> recent_events,
            List<PhaseRuntimeState> phase_states,
            List<RecoveryRecord> recovery_history
    ) {
    }

    public record VerificationRecord(
            String candidate_id,
            String candidate_value,
            String verification_status,
            Integer failure_count,
            String verifier_worker,
            String verifier_phase,
            String verifier_prompt_group,
            String verifier_tool,
            String latest_event_type,
            String latest_output,
            String latest_artifact,
            String verified_at,
            String latest_event_at
    ) {
    }

    public record ProjectVerificationState(
            String project_id,
            String completion_status,
            String health_status,
            List<VerificationRecord> records
    ) {
    }

    public record VerificationIntent(
            String id,
            String candidate_id,
            String status,
            String worker,
            String prompt_group,
            String tool,
            String summary,
            String artifact,
            String created_at,
            String updated_at
    ) {
    }

    public record PhaseRuntimeState(
            String scope_type,
            String scope_id,
            String phase,
            String artifact_focus,
            String latest_progress,
            String latest_stop_condition,
            String latest_recovery_action,
            boolean stalled
    ) {
    }

    public record RecoveryRecord(
            String scope_type,
            String scope_id,
            String phase,
            String event_type,
            String message,
            String created_at
    ) {
    }

    public record ArtifactSummary(
            String path,
            String name,
            String type,
            long size,
            String modified_at
    ) {
    }

    public record ArtifactIndexGroup(
            String key,
            String label,
            int count,
            List<ArtifactSummary> artifacts
    ) {
    }

    public record JniMethodEntry(
            String class_name,
            String method_name,
            String signature,
            String return_type,
            String source_path,
            int line,
            String source_kind,
            String probable_symbol,
            List<String> libraries
    ) {
    }

    public record ProjectReason(String worker, String trigger, String started_at, String last_heartbeat_at) {
    }

    public record ProjectMeta(
            String id,
            String title,
            String status,
            boolean bootstrap_enabled,
            String created_at,
            ProjectReason reason
    ) {
    }

    public record ProjectSummary(
            String id,
            String title,
            String status,
            boolean bootstrap_enabled,
            String created_at,
            ProjectReason reason,
            int fact_count,
            int intent_count,
            int working_intent_count,
            int unclaimed_intent_count,
            int hint_count
    ) {
    }

    public record ProjectDetail(ProjectMeta project, List<Fact> facts, List<Intent> intents, List<Hint> hints) {
    }

    public record CreateHintInline(@NotBlank String content, @NotBlank String creator) {
    }

    public record CreateProjectRequest(
            @NotBlank String title,
            @NotBlank String origin,
            @NotBlank String goal,
            Boolean bootstrap_enabled,
            @Valid List<CreateHintInline> hints
    ) {
        public boolean bootstrapEnabled() {
            return bootstrap_enabled == null || bootstrap_enabled;
        }
    }

    public record CreateHintRequest(@NotBlank String content, @NotBlank String creator) {
    }

    public record CreateRuntimeEventRequest(
            @NotBlank String scope_type,
            String scope_id,
            String worker,
            @NotBlank String event_type,
            String phase,
            String step,
            String message,
            String artifact,
            String tool,
            Integer progress_current,
            Integer progress_total
    ) {
    }

    public record CreateIntentRequest(
            @JsonProperty("from") @NotEmpty List<@NotBlank String> from,
            @NotBlank String description,
            @NotBlank String creator,
            String worker
    ) {
    }

    public record HeartbeatRequest(
            @NotBlank String worker,
            String phase,
            String step,
            String message,
            String artifact,
            String tool,
            Integer progress_current,
            Integer progress_total
    ) {
    }

    public record ReasonClaimRequest(@NotBlank String worker, @NotBlank String trigger) {
    }

    public record ConcludeRequest(@NotBlank String worker, @NotBlank String description) {
    }

    public record CompleteRequest(
            @JsonProperty("from") @NotEmpty List<@NotBlank String> from,
            @NotBlank String description,
            @NotBlank String worker
    ) {
    }

    public record FinalizeCompletionRequest(@NotBlank String worker) {
    }

    public record VerifyCompletionRequest(
            @NotBlank String worker,
            String prompt_group,
            String tool
    ) {
    }

    public record VerificationFailureRequest(
            @NotBlank String worker,
            @NotBlank String message,
            Boolean retryable
    ) {
        public boolean retryableOrDefault() {
            return retryable == null || retryable;
        }
    }

    public record RerunVerifierRequest(@NotBlank String candidate_id, @NotBlank String worker) {
    }

    public record CompletionCandidate(
            String id,
            String project_id,
            String source_intent_id,
            String worker,
            String candidate_type,
            String candidate_value,
            String evidence_summary,
            String artifact_path,
            String verification_status,
            String verifier_worker,
            String verified_at,
            String created_at
    ) {
    }

    public record ProjectHealth(
            String project_id,
            String health_status,
            String completion_status,
            String active_dispatch_profile,
            String quarantine_reason,
            String updated_at
    ) {
    }

    public record ConcludeResponse(Fact fact, Intent intent) {
    }

    public record BlackboardFactRequest(
            @NotBlank String worker,
            @NotBlank String description,
            Boolean verified,
            String intent_id
    ) {
    }

    public record BlackboardView(
            List<Fact> facts,
            List<Fact> dead_ends,
            List<Intent> open_intents,
            List<Intent> claimed_intents,
            List<Hint> hints
    ) {
    }

    public record UpdateProjectStatusRequest(@NotNull @Size(min = 1) String status) {
    }

    public record UpdateProjectTitleRequest(@NotBlank String title) {
    }

    public record ResumeExecutionRequest(@NotBlank String creator) {
    }

    public record ReopenRequest(@NotBlank String description, @NotBlank String creator) {
    }

    public record ReopenResponse(ProjectMeta project, Fact fact, Intent intent) {
    }

    public record QuarantineRequest(@NotBlank String reason, @NotBlank String worker) {
    }

    public record RepairCompletionRequest(@NotBlank String worker) {
    }

    public record RepairCompletionResponse(ProjectMeta project, ProjectHealth health, int removed_goal_intents) {
    }

    public record AutomationJob(
            String id,
            String job_type,
            boolean enabled,
            String schedule,
            String payload,
            String last_run_at,
            String last_status,
            String last_message
    ) {
    }

    public record AutomationRunResponse(String status, int scanned, int findings, List<String> messages) {
    }

    public record ProcessStatus(
            String name,
            String pid_file,
            String lock_file,
            String pid,
            boolean running,
            String profile,
            String config,
            String log_file
    ) {
    }

    public record DependencyStatus(
            String name,
            boolean healthy,
            String status,
            String detail
    ) {
    }

    public record LockStatus(
            String path,
            String kind,
            String pid,
            boolean running,
            boolean stale,
            String profile,
            String config
    ) {
    }

    public record SystemStatus(
            String server_url,
            String data_dir,
            String dispatcher_config,
            String runtime_schema,
            String runtime_schema_version,
            ProcessStatus serve,
            List<ProcessStatus> dispatches,
            List<AutomationJob> automation_jobs,
            List<DependencyStatus> dependencies,
            List<LockStatus> locks
    ) {
    }

    public record ApiResult(String status) {
    }
}
