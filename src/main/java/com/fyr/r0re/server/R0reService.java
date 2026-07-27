package com.fyr.r0re.server;

import com.fyr.r0re.protocol.Models.*;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.fyr.r0re.config.R0reProperties;

@Service
public class R0reService {
    private final JdbcTemplate jdbc;
    private final CompletionPolicyService completionPolicy;
    private final R0reProperties properties;

    @Autowired
    public R0reService(JdbcTemplate jdbc, CompletionPolicyService completionPolicy, R0reProperties properties) {
        this.jdbc = jdbc;
        this.completionPolicy = completionPolicy;
        this.properties = properties;
    }

    public R0reService(JdbcTemplate jdbc) {
        this(jdbc, new CompletionPolicyService(jdbc, new R0reProperties(), "./datas/r0re", Path.of(System.getProperty("user.dir"))), new R0reProperties());
    }

    public String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    @Transactional
    public List<ProjectSummary> listProjects() {
        expireWorkers(null);
        expireReasonLeases(null);
        return jdbc.query("""
                SELECT p.*,
                    (SELECT COUNT(*) FROM facts WHERE project_id = p.id) AS fact_count,
                    (SELECT COUNT(*) FROM intents WHERE project_id = p.id) AS intent_count,
                    (SELECT COUNT(*) FROM intents WHERE project_id = p.id AND concluded_at IS NULL AND worker IS NOT NULL) AS working_intent_count,
                    (SELECT COUNT(*) FROM intents WHERE project_id = p.id AND concluded_at IS NULL AND worker IS NULL) AS unclaimed_intent_count,
                    (SELECT COUNT(*) FROM hints WHERE project_id = p.id) AS hint_count
                FROM projects p
                ORDER BY p.created_at
                """, (rs, rowNum) -> new ProjectSummary(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getInt("bootstrap_enabled") != 0,
                rs.getString("created_at"),
                reason(rs),
                rs.getInt("fact_count"),
                rs.getInt("intent_count"),
                rs.getInt("working_intent_count"),
                rs.getInt("unclaimed_intent_count"),
                rs.getInt("hint_count")
        ));
    }

    @Transactional
    public ProjectDetail createProject(CreateProjectRequest body) {
        String id = nextProjectId();
        String now = now();
        jdbc.update(
                "INSERT INTO projects (id, title, status, bootstrap_enabled, created_at) VALUES (?, ?, 'active', ?, ?)",
                id, body.title().trim(), body.bootstrapEnabled() ? 1 : 0, now
        );
        jdbc.update("""
                INSERT INTO project_health (project_id, health_status, completion_status, updated_at)
                VALUES (?, 'normal', 'none', ?)
                """, id, now);
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "origin", id, body.origin().trim());
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "goal", id, body.goal().trim());

        List<Hint> hints = new ArrayList<>();
        if (body.hints() != null) {
            for (CreateHintInline hint : body.hints()) {
                String hid = nextScopedId("hint", "h", id);
                jdbc.update(
                        "INSERT INTO hints (id, project_id, content, creator, created_at) VALUES (?, ?, ?, ?, ?)",
                        hid, id, hint.content().trim(), hint.creator().trim(), now
                );
                hints.add(new Hint(hid, hint.content().trim(), hint.creator().trim(), now));
            }
        }
        return new ProjectDetail(
                new ProjectMeta(id, body.title().trim(), "active", body.bootstrapEnabled(), now, null),
                List.of(new Fact("origin", body.origin().trim()), new Fact("goal", body.goal().trim())),
                List.of(),
                hints
        );
    }

    @Transactional
    public ProjectDetail getProject(String projectId) {
        expireWorkers(projectId);
        expireReasonLeases(projectId);
        var project = projectMeta(projectId);
        var facts = jdbc.query("SELECT * FROM facts WHERE project_id = ? ORDER BY rowid", factMapper(), projectId);
        var intents = buildIntents(projectId);
        var hints = jdbc.query("SELECT id, content, creator, created_at FROM hints WHERE project_id = ? ORDER BY created_at", hintMapper(), projectId);
        return new ProjectDetail(project, facts, intents, hints);
    }

    @Transactional
    public ProjectRuntime projectRuntime(String projectId, int eventLimit) {
        expireWorkers(projectId);
        expireReasonLeases(projectId);
        ProjectMeta project = projectMeta(projectId);
        List<Intent> intents = buildIntents(projectId).stream()
                .filter(intent -> intent.concluded_at() == null)
                .toList();
        List<RuntimeEvent> recentEvents = runtimeEvents(projectId, eventLimit);
        Map<String, RuntimeEvent> latestIntentEvent = recentEvents.stream()
                .filter(event -> "intent".equals(event.scope_type()) && event.scope_id() != null && !event.scope_id().isBlank())
                .collect(Collectors.toMap(RuntimeEvent::scope_id, event -> event, this::laterEvent));
        RuntimeEvent latestReasonEvent = recentEvents.stream()
                .filter(event -> "reason".equals(event.scope_type()))
                .max(Comparator.comparing(RuntimeEvent::created_at))
                .orElse(null);
        int intentTimeout = settings().intent_timeout();
        int reasonTimeout = settings().reason_timeout();
        List<RuntimeScope> intentScopes = intents.stream()
                .map(intent -> scopeFromIntent(intent, latestIntentEvent.get(intent.id()), intentTimeout))
                .toList();
        RuntimeScope reasonScope = project.reason() == null
                ? null
                : scopeFromReason(project.reason(), latestReasonEvent, reasonTimeout);
        List<RuntimeScope> verificationScopes = projectVerificationState(projectId).records().stream()
                .map(record -> scopeFromVerification(record, reasonTimeout))
                .toList();
        List<PhaseRuntimeState> phaseStates = new ArrayList<>();
        if (reasonScope != null) {
            phaseStates.add(phaseRuntimeState(reasonScope, recentEvents));
        }
        intentScopes.forEach(scope -> phaseStates.add(phaseRuntimeState(scope, recentEvents)));
        verificationScopes.forEach(scope -> phaseStates.add(phaseRuntimeState(scope, recentEvents)));
        List<RecoveryRecord> recoveryHistory = recentEvents.stream()
                .filter(event -> event.event_type() != null && event.event_type().startsWith("task_recovery"))
                .map(event -> new RecoveryRecord(
                        event.scope_type(),
                        event.scope_id(),
                        event.phase(),
                        event.event_type(),
                        firstNonBlank(event.message(), event.step()),
                        event.created_at()
                ))
                .toList();
        return new ProjectRuntime("harness-loop.v1", project, intentScopes, verificationScopes, reasonScope, recentEvents, phaseStates, recoveryHistory);
    }

    @Transactional
    public ProjectVerificationState projectVerificationState(String projectId) {
        requireProject(projectId);
        ProjectHealth health = projectHealth(projectId);
        List<CompletionCandidate> candidates = completionCandidates(projectId);
        List<RuntimeEvent> events = runtimeEvents(projectId, 120);
        List<VerificationRecord> records = candidates.stream()
                .map(candidate -> verificationRecord(candidate, events))
                .toList();
        return new ProjectVerificationState(
                projectId,
                health.completion_status(),
                health.health_status(),
                records
        );
    }

    @Transactional
    public List<VerificationIntent> projectVerificationIntents(String projectId) {
        requireProject(projectId);
        ensureVerificationIntents(projectId);
        return jdbc.query("""
                SELECT * FROM verification_intents
                WHERE project_id = ?
                ORDER BY updated_at DESC, id DESC
                """, verificationIntentMapper(), projectId);
    }

    @Transactional
    public List<RuntimeEvent> runtimeEvents(String projectId, int limit) {
        requireProject(projectId);
        int resolvedLimit = Math.max(1, limit);
        return jdbc.query("""
                        SELECT * FROM runtime_events
                        WHERE project_id = ?
                        ORDER BY id DESC
                        LIMIT ?
                        """,
                runtimeEventMapper(), projectId, resolvedLimit);
    }

    @Transactional
    public RuntimeEvent appendRuntimeEvent(String projectId, CreateRuntimeEventRequest body) {
        requireProject(projectId);
        String createdAt = now();
        insertRuntimeEvent(
                projectId,
                body.scope_type(),
                body.scope_id(),
                body.worker(),
                body.event_type(),
                body.phase(),
                body.step(),
                body.message(),
                body.artifact(),
                body.tool(),
                body.progress_current(),
                body.progress_total(),
                createdAt
        );
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return new RuntimeEvent(
                id == null ? 0L : id,
                projectId,
                body.scope_type(),
                body.scope_id(),
                blankToNull(body.worker()),
                body.event_type(),
                blankToNull(body.phase()),
                blankToNull(body.step()),
                blankToNull(body.message()),
                blankToNull(body.artifact()),
                blankToNull(body.tool()),
                body.progress_current(),
                body.progress_total(),
                createdAt
        );
    }

    @Transactional
    public void deleteProject(String projectId) {
        requireProject(projectId);
        jdbc.update("DELETE FROM projects WHERE id = ?", projectId);
    }

    @Transactional
    public ProjectMeta updateTitle(String projectId, UpdateProjectTitleRequest body) {
        requireProject(projectId);
        jdbc.update("UPDATE projects SET title = ? WHERE id = ?", body.title().trim(), projectId);
        return projectMeta(projectId);
    }

    @Transactional
    public ProjectMeta updateStatus(String projectId, UpdateProjectStatusRequest body) {
        if (!body.status().equals("active") && !body.status().equals("stopped")) {
            throw HttpErrors.badRequest("status must be active or stopped");
        }
        var row = requireProject(projectId);
        if (row.status().equals("completed")) {
            throw HttpErrors.conflict("Completed projects cannot change status");
        }
        if ("active".equals(body.status())) {
            resumeStoppedProject(projectId);
        } else {
            jdbc.update("UPDATE projects SET status = ? WHERE id = ?", body.status(), projectId);
        }
        clearOpenIntentLeases(projectId);
        clearReason(projectId);
        if ("active".equals(body.status())) {
            requestDispatcherWake("update_status_active", projectId);
        }
        return projectMeta(projectId);
    }

    @Transactional
    public ProjectMeta resumeExecution(String projectId, ResumeExecutionRequest body) {
        var row = requireProject(projectId);
        if (row.status().equals("completed")) {
            throw HttpErrors.conflict("Completed projects cannot resume execution");
        }
        resumeStoppedProject(projectId);
        clearOpenIntentLeases(projectId);
        clearReason(projectId);
        recordRuntimeEvent(projectId, "project", projectId, body.creator().trim(), "project_execution_resumed",
                new HeartbeatRequest(body.creator().trim(), "resume", "manual_resume", "execution resumed from WebUI", null, null, null, null), now());
        requestDispatcherWake("resume_execution", projectId);
        return projectMeta(projectId);
    }

    @Transactional
    public Intent createIntent(String projectId, CreateIntentRequest body) {
        checkProjectActive(projectId);
        validateFacts(projectId, body.from());
        if (body.from().contains("goal")) {
            throw HttpErrors.badRequest("goal cannot be used in from");
        }
        if (body.worker() != null && !body.worker().isBlank() && !body.worker().trim().equals(body.creator().trim())) {
            throw HttpErrors.badRequest("worker must be null or equal to creator");
        }
        String id = nextScopedId("intent", "i", projectId);
        String now = now();
        String worker = body.worker() == null || body.worker().isBlank() ? null : body.worker().trim();
        jdbc.update("""
                INSERT INTO intents (id, project_id, to_fact_id, description, creator, worker, last_heartbeat_at, created_at, concluded_at)
                VALUES (?, ?, NULL, ?, ?, ?, ?, ?, NULL)
                """, id, projectId, body.description().trim(), body.creator().trim(), worker, worker == null ? null : now, now);
        for (String fact : body.from()) {
            jdbc.update("INSERT INTO intent_sources (intent_id, project_id, fact_id) VALUES (?, ?, ?)", id, projectId, fact);
        }
        return intent(id, projectId);
    }

    @Transactional
    public Intent heartbeat(String projectId, String intentId, HeartbeatRequest body) {
        checkProjectActive(projectId);
        claimableIntent(projectId, intentId, body.worker());
        String now = now();
        jdbc.update("UPDATE intents SET worker = ?, last_heartbeat_at = ? WHERE id = ? AND project_id = ?",
                body.worker().trim(), now, intentId, projectId);
        recordRuntimeEvent(projectId, "intent", intentId, body.worker().trim(), "intent_heartbeat", body, now);
        return intent(intentId, projectId);
    }

    @Transactional
    public Intent release(String projectId, String intentId, HeartbeatRequest body) {
        checkProjectActive(projectId);
        var row = openIntent(projectId, intentId);
        if (row.worker() != null && !row.worker().equals(body.worker().trim())) {
            throw HttpErrors.conflict("Intent is currently claimed by " + row.worker());
        }
        if (row.worker() != null) {
            jdbc.update("UPDATE intents SET worker = NULL WHERE id = ? AND project_id = ?", intentId, projectId);
            recordRuntimeEvent(projectId, "intent", intentId, body.worker().trim(), "intent_released", body, now());
        }
        return intent(intentId, projectId);
    }

    @Transactional
    public ConcludeResponse conclude(String projectId, String intentId, ConcludeRequest body) {
        checkProjectActive(projectId);
        claimableIntent(projectId, intentId, body.worker());
        String factId = nextScopedId("fact", "f", projectId);
        String now = now();
        jdbc.update("INSERT INTO facts (id, project_id, description, creator) VALUES (?, ?, ?, ?)",
                factId, projectId, body.description().trim(), body.worker().trim());
        jdbc.update("""
                UPDATE intents
                SET to_fact_id = ?, worker = ?, last_heartbeat_at = ?, concluded_at = ?
                WHERE id = ? AND project_id = ?
                """, factId, body.worker().trim(), now, now, intentId, projectId);
        recordRuntimeEvent(projectId, "intent", intentId, body.worker().trim(), "intent_concluded",
                new HeartbeatRequest(body.worker(), null, null, "fact=" + factId, null, null, null, null), now);
        return new ConcludeResponse(new Fact(factId, body.description().trim(), "fact", false, body.worker().trim()), intent(intentId, projectId));
    }

    @Transactional
    public Fact blackboardAddFact(String projectId, BlackboardFactRequest body) {
        return blackboardAdd(projectId, body, "fact", Boolean.TRUE.equals(body.verified()), "blackboard_fact");
    }

    @Transactional
    public Fact blackboardAddDeadEnd(String projectId, BlackboardFactRequest body) {
        return blackboardAdd(projectId, body, "dead_end", false, "blackboard_deadend");
    }

    private Fact blackboardAdd(String projectId, BlackboardFactRequest body, String kind, boolean verified, String eventType) {
        checkProjectActive(projectId);
        String description = body.description().trim();
        if (description.isEmpty()) {
            throw HttpErrors.badRequest("description must not be blank");
        }
        String worker = body.worker().trim();
        String normalized = normalizeFactDescription(description);
        var existing = jdbc.query("SELECT * FROM facts WHERE project_id = ? AND kind = ? ORDER BY rowid", factMapper(), projectId, kind);
        for (Fact fact : existing) {
            if (normalizeFactDescription(fact.description()).equals(normalized)) {
                return fact;
            }
        }
        String factId = nextScopedId("fact", "f", projectId);
        String now = now();
        jdbc.update("INSERT INTO facts (id, project_id, description, kind, verified, creator) VALUES (?, ?, ?, ?, ?, ?)",
                factId, projectId, description, kind, verified ? 1 : 0, worker);
        String scopeType = body.intent_id() == null || body.intent_id().isBlank() ? "project" : "intent";
        String scopeId = "intent".equals(scopeType) ? body.intent_id().trim() : projectId;
        recordRuntimeEvent(projectId, scopeType, scopeId, worker, eventType,
                new HeartbeatRequest(worker, null, null, "fact=" + factId, null, null, null, null), now);
        return new Fact(factId, description, kind, verified, worker);
    }

    @Transactional
    public BlackboardView blackboardView(String projectId) {
        requireProject(projectId);
        var facts = jdbc.query("SELECT * FROM facts WHERE project_id = ? AND kind = 'fact' ORDER BY rowid", factMapper(), projectId);
        var deadEnds = jdbc.query("SELECT * FROM facts WHERE project_id = ? AND kind = 'dead_end' ORDER BY rowid", factMapper(), projectId);
        var openIntents = jdbc.query("SELECT * FROM intents WHERE project_id = ? AND to_fact_id IS NULL AND worker IS NULL ORDER BY created_at",
                intentMapper(projectId), projectId);
        var claimedIntents = jdbc.query("SELECT * FROM intents WHERE project_id = ? AND to_fact_id IS NULL AND worker IS NOT NULL ORDER BY created_at",
                intentMapper(projectId), projectId);
        var hints = jdbc.query("SELECT id, content, creator, created_at FROM hints WHERE project_id = ? ORDER BY created_at", hintMapper(), projectId);
        return new BlackboardView(facts, deadEnds, openIntents, claimedIntents, hints);
    }

    private String normalizeFactDescription(String description) {
        return description.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    @Transactional
    public ProjectMeta claimReason(String projectId, ReasonClaimRequest body) {
        checkProjectActive(projectId);
        expireReasonLeases(projectId);
        var row = requireProject(projectId);
        if (row.reasonWorker() != null && !row.reasonWorker().equals(body.worker().trim())) {
            throw HttpErrors.conflict("Project reason is currently claimed by " + row.reasonWorker());
        }
        String now = now();
        jdbc.update("""
                UPDATE projects
                SET reason_worker = ?, reason_trigger = ?, reason_started_at = ?, reason_last_heartbeat_at = ?
                WHERE id = ?
                """, body.worker().trim(), body.trigger().trim(), now, now, projectId);
        recordRuntimeEvent(projectId, "reason", "reason", body.worker().trim(), "reason_claimed",
                new HeartbeatRequest(body.worker(), "reason", body.trigger().trim(), "reason claimed", null, null, null, null), now);
        return projectMeta(projectId);
    }

    @Transactional
    public ProjectMeta reasonHeartbeat(String projectId, HeartbeatRequest body) {
        checkProjectActive(projectId);
        expireReasonLeases(projectId);
        var row = requireProject(projectId);
        if (row.reasonWorker() == null) {
            throw HttpErrors.conflict("Project reason is not currently claimed");
        }
        if (!row.reasonWorker().equals(body.worker().trim())) {
            throw HttpErrors.conflict("Project reason is currently claimed by " + row.reasonWorker());
        }
        String now = now();
        jdbc.update("UPDATE projects SET reason_last_heartbeat_at = ? WHERE id = ?", now, projectId);
        recordRuntimeEvent(projectId, "reason", "reason", body.worker().trim(), "reason_heartbeat", body, now);
        return projectMeta(projectId);
    }

    @Transactional
    public ProjectMeta releaseReason(String projectId, HeartbeatRequest body) {
        var row = requireProject(projectId);
        if (row.reasonWorker() != null && !row.reasonWorker().equals(body.worker().trim())) {
            throw HttpErrors.conflict("Project reason is currently claimed by " + row.reasonWorker());
        }
        clearReason(projectId);
        recordRuntimeEvent(projectId, "reason", "reason", body.worker().trim(), "reason_released", body, now());
        return projectMeta(projectId);
    }

    @Transactional
    public CompletionCandidate complete(String projectId, CompleteRequest body) {
        checkProjectActive(projectId);
        validateFacts(projectId, body.from());
        if (body.from().contains("goal")) {
            throw HttpErrors.badRequest("goal cannot be used in from");
        }
        String candidateId = nextScopedId("candidate", "c", projectId);
        String now = now();
        jdbc.update("""
                INSERT INTO completion_candidates (
                    id, project_id, source_intent_id, worker, candidate_type, candidate_value,
                    evidence_summary, artifact_path, verification_status, verifier_worker, verified_at, created_at
                ) VALUES (?, ?, NULL, ?, 'goal', ?, ?, NULL, 'pending', NULL, NULL, ?)
                """, candidateId, projectId, body.worker().trim(), body.description().trim(), String.join(",", body.from()), now);
        ensureProjectHealth(projectId, now);
        jdbc.update("""
                UPDATE project_health
                SET completion_status = 'candidate_pending', updated_at = ?
                WHERE project_id = ?
                """, now, projectId);
        clearReason(projectId);
        recordRuntimeEvent(projectId, "project", projectId, body.worker().trim(), "completion_candidate_created",
                new HeartbeatRequest(body.worker(), "report", "candidate", "completion candidate " + candidateId + " created", null, null, null, null), now);
        return completionCandidate(candidateId, projectId);
    }

    @Transactional
    public List<CompletionCandidate> completionCandidates(String projectId) {
        requireProject(projectId);
        return jdbc.query("""
                SELECT * FROM completion_candidates
                WHERE project_id = ?
                ORDER BY created_at DESC, id DESC
                """, completionCandidateMapper(), projectId);
    }

    @Transactional
    public CompletionCandidate approveCompletionCandidate(String projectId, String candidateId) {
        requireProject(projectId);
        CompletionCandidate candidate = completionCandidate(candidateId, projectId);
        if (!"pending".equals(candidate.verification_status())
                && !"verifying".equals(candidate.verification_status())
                && !"retryable_failed".equals(candidate.verification_status())
                && !"manual_review".equals(candidate.verification_status())) {
            throw HttpErrors.conflict("Only pending, verifying, retryable_failed, or manual_review candidates can be approved");
        }
        String now = now();
        jdbc.update("""
                UPDATE completion_candidates
                SET verification_status = 'approved', verified_at = ?
                WHERE id = ? AND project_id = ?
                """, now, candidate.id(), projectId);
        ensureProjectHealth(projectId, now);
        jdbc.update("""
                UPDATE project_health
                SET completion_status = 'approved', updated_at = ?
                WHERE project_id = ?
                """, now, projectId);
        VerificationIntent verificationIntent = verificationIntent(projectId, candidate.id());
        upsertVerificationIntent(
                projectId,
                candidate.id(),
                "approved",
                firstNonBlank(candidate.verifier_worker(), verificationIntent.worker()),
                verificationIntent.prompt_group(),
                verificationIntent.tool(),
                "completion candidate " + candidate.id() + " approved",
                firstNonBlank(candidate.artifact_path(), verificationIntent.artifact()),
                candidate.created_at(),
                now
        );
        recordRuntimeEvent(projectId, "project", projectId, candidate.worker(), "completion_candidate_approved",
                new HeartbeatRequest(candidate.worker(), "verify", "approve", "completion candidate " + candidate.id() + " approved", null, null, null, null), now);
        return completionCandidate(candidate.id(), projectId);
    }

    @Transactional
    public CompletionCandidate startCompletionVerification(String projectId, String candidateId, VerifyCompletionRequest body) {
        requireProject(projectId);
        CompletionCandidate candidate = completionCandidate(candidateId, projectId);
        if (!"pending".equals(candidate.verification_status())) {
            throw HttpErrors.conflict("Only pending candidates can enter verification");
        }
        String now = now();
        String worker = body.worker().trim();
        jdbc.update("""
                UPDATE completion_candidates
                SET verification_status = 'verifying', verifier_worker = ?
                WHERE id = ? AND project_id = ?
                """, worker, candidate.id(), projectId);
        ensureProjectHealth(projectId, now);
        jdbc.update("""
                UPDATE project_health
                SET completion_status = 'verifying', updated_at = ?
                WHERE project_id = ?
                """, now, projectId);
        upsertVerificationIntent(
                projectId,
                candidate.id(),
                "verifying",
                worker,
                body.prompt_group(),
                body.tool(),
                "completion candidate " + candidate.id() + " verification started",
                candidate.artifact_path(),
                candidate.created_at(),
                now
        );
        recordRuntimeEvent(projectId, "project", projectId, worker, "completion_candidate_verifying",
                new HeartbeatRequest(worker, "verify", "start", "completion candidate " + candidate.id() + " verification started", null, null, null, null), now);
        return completionCandidate(candidate.id(), projectId);
    }

    @Transactional
    public CompletionCandidate rerunVerifier(String projectId, RerunVerifierRequest body) {
        requireProject(projectId);
        CompletionCandidate candidate = completionCandidate(body.candidate_id(), projectId);
        if ("finalized".equals(candidate.verification_status())) {
            throw HttpErrors.conflict("Finalized candidates cannot re-run verifier");
        }
        String now = now();
        String worker = body.worker().trim();
        jdbc.update("""
                UPDATE completion_candidates
                SET verification_status = 'pending',
                    verifier_worker = NULL,
                    verified_at = NULL
                WHERE id = ? AND project_id = ?
                """, candidate.id(), projectId);
        ensureProjectHealth(projectId, now);
        jdbc.update("""
                UPDATE project_health
                SET health_status = 'normal',
                    completion_status = 'candidate_pending',
                    quarantine_reason = CASE
                        WHEN quarantine_reason LIKE 'completion candidate rejected:%' THEN NULL
                        ELSE quarantine_reason
                    END,
                    updated_at = ?
                WHERE project_id = ?
                """, now, projectId);
        upsertVerificationIntent(
                projectId,
                candidate.id(),
                "pending",
                null,
                null,
                null,
                "completion candidate " + candidate.id() + " queued for verifier rerun",
                candidate.artifact_path(),
                candidate.created_at(),
                now
        );
        recordRuntimeEvent(projectId, "project", projectId, worker, "completion_candidate_rerun_requested",
                new HeartbeatRequest(worker, "verify", "rerun", "completion candidate " + candidate.id() + " queued for verifier rerun", null, "rule-verifier", null, null), now);
        return completionCandidate(candidate.id(), projectId);
    }

    @Transactional
    public CompletionCandidate failCompletionVerification(String projectId, String candidateId, VerificationFailureRequest body) {
        requireProject(projectId);
        CompletionCandidate candidate = completionCandidate(candidateId, projectId);
        if ("finalized".equals(candidate.verification_status())) {
            throw HttpErrors.conflict("Finalized candidates cannot fail verification");
        }
        String now = now();
        String worker = body.worker().trim();
        int failureCount = verificationFailureCount(projectId, candidate.id()) + 1;
        boolean manualReview = !body.retryableOrDefault() || failureCount >= 3;
        String status = manualReview ? "manual_review" : "retryable_failed";
        jdbc.update("""
                UPDATE completion_candidates
                SET verification_status = ?, verifier_worker = ?, verified_at = ?
                WHERE id = ? AND project_id = ?
                """, status, worker, now, candidate.id(), projectId);
        ensureProjectHealth(projectId, now);
        jdbc.update("""
                UPDATE project_health
                SET health_status = CASE WHEN ? = 'manual_review' THEN 'needs_review' ELSE 'normal' END,
                    completion_status = ?,
                    quarantine_reason = CASE
                        WHEN ? = 'manual_review' THEN ?
                        ELSE CASE WHEN quarantine_reason LIKE 'verifier failure:%' THEN NULL ELSE quarantine_reason END
                    END,
                    updated_at = ?
                WHERE project_id = ?
                """, status, status,
                status,
                "verifier failure: " + candidate.id() + " count=" + failureCount,
                now,
                projectId);
        VerificationIntent verificationIntent = verificationIntent(projectId, candidate.id());
        upsertVerificationIntent(
                projectId,
                candidate.id(),
                status,
                worker,
                verificationIntent.prompt_group(),
                verificationIntent.tool(),
                body.message().trim(),
                firstNonBlank(candidate.artifact_path(), verificationIntent.artifact()),
                candidate.created_at(),
                now
        );
        recordRuntimeEvent(projectId, "project", projectId, worker, "completion_candidate_verification_failed",
                new HeartbeatRequest(worker, "verify", status, "completion candidate " + candidate.id() + " verifier failed: " + body.message().trim(), null, "rule-verifier", failureCount, 3), now);
        return completionCandidate(candidate.id(), projectId);
    }

    @Transactional
    public CompletionCandidate rejectCompletionCandidate(String projectId, String candidateId) {
        requireProject(projectId);
        CompletionCandidate candidate = completionCandidate(candidateId, projectId);
        if ("finalized".equals(candidate.verification_status())) {
            throw HttpErrors.conflict("Finalized candidates cannot be rejected");
        }
        if (!"pending".equals(candidate.verification_status())
                && !"verifying".equals(candidate.verification_status())
                && !"approved".equals(candidate.verification_status())
                && !"retryable_failed".equals(candidate.verification_status())
                && !"manual_review".equals(candidate.verification_status())) {
            throw HttpErrors.conflict("Only pending, verifying, approved, retryable_failed, or manual_review candidates can be rejected");
        }
        String now = now();
        jdbc.update("""
                UPDATE completion_candidates
                SET verification_status = 'rejected', verified_at = ?
                WHERE id = ? AND project_id = ?
                """, now, candidate.id(), projectId);
        ensureProjectHealth(projectId, now);
        jdbc.update("""
                UPDATE project_health
                SET health_status = 'needs_review',
                    completion_status = 'rejected',
                    quarantine_reason = ?,
                    updated_at = ?
                WHERE project_id = ?
                """, "completion candidate rejected: " + candidate.id(), now, projectId);
        VerificationIntent verificationIntent = verificationIntent(projectId, candidate.id());
        upsertVerificationIntent(
                projectId,
                candidate.id(),
                "rejected",
                firstNonBlank(candidate.verifier_worker(), verificationIntent.worker()),
                verificationIntent.prompt_group(),
                verificationIntent.tool(),
                "completion candidate " + candidate.id() + " rejected",
                firstNonBlank(candidate.artifact_path(), verificationIntent.artifact()),
                candidate.created_at(),
                now
        );
        recordRuntimeEvent(projectId, "project", projectId, candidate.worker(), "completion_candidate_rejected",
                new HeartbeatRequest(candidate.worker(), "verify", "reject", "completion candidate " + candidate.id() + " rejected", null, null, null, null), now);
        return completionCandidate(candidate.id(), projectId);
    }

    @Transactional
    public Intent finalizeCompletion(String projectId, String candidateId, FinalizeCompletionRequest body) {
        var project = requireProject(projectId);
        if ("completed".equals(project.status())) {
            throw HttpErrors.conflict("Project is already completed");
        }
        CompletionCandidate candidate = completionCandidate(candidateId, projectId);
        if (!"approved".equals(candidate.verification_status())) {
            throw HttpErrors.conflict("Completion candidate must be approved before finalizing");
        }
        completionPolicy.assertCandidateCanFinalize(projectId, getProject(projectId), candidate);
        List<String> sources = candidateSources(candidate);
        validateFacts(projectId, sources);
        if (sources.contains("goal")) {
            throw HttpErrors.badRequest("goal cannot be used in from");
        }
        String intentId = nextScopedId("intent", "i", projectId);
        String now = now();
        String worker = body.worker().trim();
        jdbc.update("""
                INSERT INTO intents (id, project_id, to_fact_id, description, creator, worker, last_heartbeat_at, created_at, concluded_at)
                VALUES (?, ?, 'goal', ?, ?, ?, ?, ?, ?)
                """, intentId, projectId, candidate.candidate_value(), worker, worker, now, now, now);
        for (String fact : sources) {
            jdbc.update("INSERT INTO intent_sources (intent_id, project_id, fact_id) VALUES (?, ?, ?)", intentId, projectId, fact);
        }
        jdbc.update("""
                UPDATE completion_candidates
                SET verification_status = 'finalized', verifier_worker = ?, verified_at = ?
                WHERE id = ? AND project_id = ?
                """, worker, now, candidate.id(), projectId);
        ensureProjectHealth(projectId, now);
        jdbc.update("""
                UPDATE project_health
                SET completion_status = 'finalized', updated_at = ?
                WHERE project_id = ?
                """, now, projectId);
        VerificationIntent verificationIntent = verificationIntent(projectId, candidate.id());
        upsertVerificationIntent(
                projectId,
                candidate.id(),
                "finalized",
                worker,
                verificationIntent.prompt_group(),
                verificationIntent.tool(),
                "completion candidate " + candidate.id() + " finalized",
                firstNonBlank(candidate.artifact_path(), verificationIntent.artifact()),
                candidate.created_at(),
                now
        );
        jdbc.update("UPDATE projects SET status = 'completed' WHERE id = ?", projectId);
        clearReason(projectId);
        recordRuntimeEvent(projectId, "project", projectId, worker, "project_completed",
                new HeartbeatRequest(worker, "finalize", "complete", "completion candidate " + candidate.id() + " finalized", null, null, null, null), now);
        return intent(intentId, projectId);
    }

    @Transactional
    public ProjectHealth projectHealth(String projectId) {
        requireProject(projectId);
        ensureProjectHealth(projectId, now());
        return jdbc.queryForObject("SELECT * FROM project_health WHERE project_id = ?", projectHealthMapper(), projectId);
    }

    @Transactional
    public ProjectHealth quarantineProject(String projectId, QuarantineRequest body) {
        requireProject(projectId);
        String now = now();
        ensureProjectHealth(projectId, now);
        jdbc.update("""
                UPDATE project_health
                SET health_status = 'quarantined',
                    quarantine_reason = ?,
                    updated_at = ?
                WHERE project_id = ?
                """, body.reason().trim(), now, projectId);
        clearReason(projectId);
        jdbc.update("UPDATE intents SET worker = NULL WHERE project_id = ? AND concluded_at IS NULL", projectId);
        recordRuntimeEvent(projectId, "project", projectId, body.worker().trim(), "project_quarantined",
                new HeartbeatRequest(body.worker().trim(), "repair", "quarantine", body.reason().trim(), null, null, null, null), now);
        return projectHealth(projectId);
    }

    @Transactional
    public ProjectHealth unquarantineProject(String projectId, QuarantineRequest body) {
        requireProject(projectId);
        String now = now();
        ensureProjectHealth(projectId, now);
        jdbc.update("""
                UPDATE project_health
                SET health_status = 'normal',
                    quarantine_reason = NULL,
                    updated_at = ?
                WHERE project_id = ?
                """, now, projectId);
        recordRuntimeEvent(projectId, "project", projectId, body.worker().trim(), "project_unquarantined",
                new HeartbeatRequest(body.worker().trim(), "repair", "unquarantine", body.reason().trim(), null, null, null, null), now);
        return projectHealth(projectId);
    }

    @Transactional
    public RepairCompletionResponse repairCompletion(String projectId, RepairCompletionRequest body) {
        var project = requireProject(projectId);
        String now = now();
        ensureProjectHealth(projectId, now);
        List<IntentRow> completions = jdbc.query(
                "SELECT * FROM intents WHERE project_id = ? AND to_fact_id = 'goal' ORDER BY created_at DESC, id DESC",
                intentRowMapper(), projectId);
        int removed = 0;
        if (completions.size() > 1) {
            for (int i = 1; i < completions.size(); i++) {
                jdbc.update("DELETE FROM intents WHERE id = ? AND project_id = ?", completions.get(i).id(), projectId);
                removed++;
            }
        }
        if ("completed".equals(project.status()) && completions.isEmpty()) {
            jdbc.update("UPDATE projects SET status = 'active' WHERE id = ?", projectId);
        }
        ProjectHealth health = projectHealth(projectId);
        if ("rejected".equals(health.completion_status()) || "needs_review".equals(health.health_status()) || "quarantined".equals(health.health_status())) {
            jdbc.update("""
                    UPDATE project_health
                    SET health_status = 'normal',
                        completion_status = CASE
                            WHEN (SELECT status FROM projects WHERE id = ?) = 'completed' THEN 'finalized'
                            ELSE 'none'
                        END,
                        quarantine_reason = NULL,
                        updated_at = ?
                    WHERE project_id = ?
                    """, projectId, now, projectId);
        }
        clearReason(projectId);
        recordRuntimeEvent(projectId, "project", projectId, body.worker().trim(), "project_completion_repaired",
                new HeartbeatRequest(body.worker().trim(), "repair", "repair_completion", "removed_goal_intents=" + removed, null, null, null, null), now);
        return new RepairCompletionResponse(projectMeta(projectId), projectHealth(projectId), removed);
    }

    @Transactional
    public ReopenResponse reopen(String projectId, ReopenRequest body) {
        expireReasonLeases(projectId);
        var project = requireProject(projectId);
        if (!project.status().equals("completed")) {
            throw HttpErrors.forbidden("Project is " + project.status());
        }
        var completions = jdbc.query(
                "SELECT * FROM intents WHERE project_id = ? AND to_fact_id = 'goal'",
                intentRowMapper(), projectId);
        if (completions.isEmpty()) {
            throw HttpErrors.conflict("Completed project is missing its completion intent");
        }
        if (completions.size() > 1) {
            throw HttpErrors.conflict("Completed project has multiple completion intents");
        }
        String completionIntent = completions.get(0).id();
        List<String> sources = jdbc.queryForList(
                "SELECT fact_id FROM intent_sources WHERE intent_id = ? AND project_id = ? ORDER BY rowid",
                String.class, completionIntent, projectId);
        if (sources.isEmpty()) {
            throw HttpErrors.conflict("Completion intent is missing its source facts");
        }

        String factId = nextScopedId("fact", "f", projectId);
        String intentId = nextScopedId("intent", "i", projectId);
        String now = now();
        jdbc.update("DELETE FROM intents WHERE id = ? AND project_id = ?", completionIntent, projectId);
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)",
                factId, projectId, body.description().trim());
        jdbc.update("""
                INSERT INTO intents (id, project_id, to_fact_id, description, creator, worker, last_heartbeat_at, created_at, concluded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, intentId, projectId, factId, "external_feedback", body.creator().trim(), body.creator().trim(), now, now, now);
        for (String source : sources) {
            jdbc.update("INSERT INTO intent_sources (intent_id, project_id, fact_id) VALUES (?, ?, ?)",
                    intentId, projectId, source);
        }
        clearReason(projectId);
        reactivateProject(projectId);
        recordRuntimeEvent(projectId, "project", projectId, body.creator().trim(), "project_reopened",
                new HeartbeatRequest(body.creator().trim(), "reason", "reopen", "project reopened for re-analysis", null, null, null, null), now);
        requestDispatcherWake("reopen", projectId);
        return new ReopenResponse(projectMeta(projectId), new Fact(factId, body.description().trim()), intent(intentId, projectId));
    }

    public Settings settings() {
        ensureWorkspaceSyncSetting();
        return jdbc.queryForObject("SELECT intent_timeout, reason_timeout, workspace_sync_enabled FROM settings WHERE rowid = 1",
                (rs, rowNum) -> new Settings(
                        rs.getInt("intent_timeout"),
                        rs.getInt("reason_timeout"),
                        rs.getInt("workspace_sync_enabled") != 0));
    }

    @Transactional
    public Settings updateSettings(Settings settings) {
        if (settings.intent_timeout() < 0 || settings.reason_timeout() < 0) {
            throw HttpErrors.badRequest("timeouts must be >= 0");
        }
        ensureWorkspaceSyncSetting();
        jdbc.update("UPDATE settings SET intent_timeout = ?, reason_timeout = ?, workspace_sync_enabled = ? WHERE rowid = 1",
                settings.intent_timeout(), settings.reason_timeout(), settings.workspace_sync_enabled() ? 1 : 0);
        return settings();
    }

    @Transactional
    public List<AutomationJob> automationJobs() {
        ensureAutomationJobs();
        return jdbc.query("SELECT * FROM automation_jobs ORDER BY id", automationJobMapper());
    }

    @Transactional
    public List<AutomationJob> dueAutomationJobs() {
        ensureAutomationJobs();
        String now = now();
        return automationJobs().stream()
                .filter(AutomationJob::enabled)
                .filter(job -> isAutomationJobDue(job, now))
                .toList();
    }

    @Transactional
    public AutomationRunResponse runAutomation(String jobId) {
        ensureAutomationJobs();
        List<String> jobs = "all".equals(jobId)
                ? jdbc.queryForList("SELECT id FROM automation_jobs WHERE enabled = 1 ORDER BY id", String.class)
                : List.of(jobId);
        int scanned = 0;
        int findings = 0;
        List<String> messages = new ArrayList<>();
        for (String id : jobs) {
            AutomationRunResponse result = runAutomationJob(id);
            scanned += result.scanned();
            findings += result.findings();
            messages.addAll(result.messages());
        }
        return new AutomationRunResponse("ok", scanned, findings, messages);
    }

    @Transactional
    public AutomationRunResponse runDueAutomationJobs() {
        ensureAutomationJobs();
        List<String> jobs = dueAutomationJobs().stream()
                .map(AutomationJob::id)
                .toList();
        if (jobs.isEmpty()) {
            return new AutomationRunResponse("idle", 0, 0, List.of("no due automation jobs"));
        }
        int scanned = 0;
        int findings = 0;
        List<String> messages = new ArrayList<>();
        for (String id : jobs) {
            AutomationRunResponse result = runAutomationJob(id);
            scanned += result.scanned();
            findings += result.findings();
            messages.addAll(result.messages());
        }
        return new AutomationRunResponse(findings > 0 ? "findings" : "ok", scanned, findings, messages);
    }

    @Transactional
    public Hint createHint(String projectId, CreateHintRequest body) {
        var project = requireProject(projectId);
        String id = nextScopedId("hint", "h", projectId);
        String now = now();
        jdbc.update("INSERT INTO hints (id, project_id, content, creator, created_at) VALUES (?, ?, ?, ?, ?)",
                id, projectId, body.content().trim(), body.creator().trim(), now);
        if ("completed".equals(project.status())) {
            clearReason(projectId);
            reactivateProject(projectId);
            recordRuntimeEvent(projectId, "project", projectId, body.creator().trim(), "project_reactivated_by_hint",
                    new HeartbeatRequest(body.creator().trim(), "reason", "hint", "completed project reactivated by new hint", null, null, null, null), now);
        }
        if ("active".equals(project.status()) || "completed".equals(project.status())) {
            createHintFollowupIntent(projectId, body.content().trim(), body.creator().trim(), now);
            requestDispatcherWake("hint_followup_intent_created", projectId);
        }
        return new Hint(id, body.content().trim(), body.creator().trim(), now);
    }

    private void createHintFollowupIntent(String projectId, String content, String creator, String now) {
        List<String> sources = latestFactIdsForHintIntent(projectId);
        if (sources.isEmpty()) {
            return;
        }
        String intentId = nextScopedId("intent", "i", projectId);
        jdbc.update("""
                INSERT INTO intents (id, project_id, to_fact_id, description, creator, worker, last_heartbeat_at, created_at, concluded_at)
                VALUES (?, ?, NULL, ?, ?, NULL, NULL, ?, NULL)
                """, intentId, projectId, "Human hint follow-up: " + content, creator, now);
        for (String source : sources) {
            jdbc.update("INSERT INTO intent_sources (intent_id, project_id, fact_id) VALUES (?, ?, ?)",
                    intentId, projectId, source);
        }
        clearReason(projectId);
        recordRuntimeEvent(projectId, "intent", intentId, creator, "hint_followup_intent_created",
                new HeartbeatRequest(creator, "reason", "hint", "new hint created a follow-up intent", null, null, null, null), now);
    }

    private List<String> latestFactIdsForHintIntent(String projectId) {
        List<String> facts = jdbc.queryForList(
                "SELECT id FROM facts WHERE project_id = ? AND id <> 'goal' ORDER BY rowid DESC LIMIT 5",
                String.class, projectId);
        List<String> ordered = new ArrayList<>(facts);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    @Transactional
    public void deleteHint(String projectId, String hintId) {
        requireProject(projectId);
        jdbc.update("DELETE FROM hints WHERE id = ? AND project_id = ?", hintId, projectId);
    }

    public List<Intent> buildIntents(String projectId) {
        return jdbc.query("SELECT * FROM intents WHERE project_id = ? ORDER BY created_at", intentMapper(projectId), projectId);
    }

    private void clearReason(String projectId) {
        jdbc.update("""
                UPDATE projects
                SET reason_worker = NULL, reason_trigger = NULL, reason_started_at = NULL, reason_last_heartbeat_at = NULL
                WHERE id = ?
                """, projectId);
    }

    private void clearOpenIntentLeases(String projectId) {
        jdbc.update("""
                UPDATE intents
                SET worker = NULL, last_heartbeat_at = NULL
                WHERE project_id = ? AND concluded_at IS NULL
                """, projectId);
    }

    private void reactivateProject(String projectId) {
        jdbc.update("UPDATE projects SET status = 'active' WHERE id = ?", projectId);
        jdbc.update("""
                UPDATE project_health
                SET health_status = CASE WHEN health_status = 'recovery_required' THEN 'normal' ELSE health_status END,
                    completion_status = 'none',
                    updated_at = ?
                WHERE project_id = ?
                """, now(), projectId);
    }

    private void resumeStoppedProject(String projectId) {
        jdbc.update("UPDATE projects SET status = 'active' WHERE id = ?", projectId);
        jdbc.update("""
                UPDATE project_health
                SET health_status = CASE WHEN health_status = 'recovery_required' THEN 'normal' ELSE health_status END,
                    updated_at = ?
                WHERE project_id = ?
                """, now(), projectId);
    }

    private void requestDispatcherWake(String reason, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ensureDispatcherRunning(reason, projectId);
                }
            });
            return;
        }
        ensureDispatcherRunning(reason, projectId);
    }

    private void ensureDispatcherRunning(String reason, String projectId) {
        try {
            recordRuntimeEvent(projectId, "project", projectId, "r0re.system", "dispatcher_wakeup_requested",
                    new HeartbeatRequest("r0re.system", "resume", reason, "dispatcher wake requested after project state change", null, null, null, null), now());
            if (hasLiveDispatcher()) {
                return;
            }
            String config = dispatcherConfigFromLock();
            if (config == null || config.isBlank()) {
                return;
            }
            Path rootDir = Path.of(System.getProperty("user.dir"));
            ProcessBuilder builder = new ProcessBuilder(
                    "bash",
                    rootDir.resolve("scripts/r0re-dispatch.sh").toString(),
                    "--background",
                    "--skip-build",
                    "--config=" + config
            );
            builder.directory(rootDir.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(Redirect.appendTo(rootDir.resolve("output/r0re-serve.log").toFile()));
            builder.start();
        } catch (Exception exc) {
            recordRuntimeEvent(projectId, "project", projectId, "r0re.system", "dispatcher_wakeup_failed",
                    new HeartbeatRequest("r0re.system", "resume", reason, "dispatcher wake failed: " + exc.getMessage(), null, null, null, null), now());
        }
    }

    private boolean hasLiveDispatcher() {
        Path lockDir = Path.of(System.getProperty("user.dir")).resolve("output").resolve("locks");
        if (!Files.isDirectory(lockDir)) {
            return false;
        }
        try (var stream = Files.list(lockDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("r0re-dispatch-"))
                    .map(this::readPidValue)
                    .filter(Objects::nonNull)
                    .anyMatch(this::isLivePid);
        } catch (IOException ignored) {
            return false;
        }
    }

    private String dispatcherConfigFromLock() {
        Path lockDir = Path.of(System.getProperty("user.dir")).resolve("output").resolve("locks");
        if (!Files.isDirectory(lockDir)) {
            return defaultDispatcherConfig();
        }
        try (var stream = Files.list(lockDir)) {
            String lockConfig = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("r0re-dispatch-"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(this::readLockValueForConfig)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse(null);
            return lockConfig != null ? lockConfig : defaultDispatcherConfig();
        } catch (IOException ignored) {
            return defaultDispatcherConfig();
        }
    }

    private String defaultDispatcherConfig() {
        String config = properties.getDispatcher().getConfig();
        if (config == null || config.isBlank()) {
            return null;
        }
        Path path = Path.of(config.trim());
        if (path.isAbsolute()) {
            return path.toString();
        }
        return Path.of(System.getProperty("user.dir")).resolve(path).toString();
    }

    private String readLockValueForConfig(Path path) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(path)) {
                if (line.startsWith("config=")) {
                    String value = line.substring(7).trim();
                    return value.isBlank() ? null : value;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private void expireWorkers(String projectId) {
        int timeoutSeconds = settings().intent_timeout();
        if (timeoutSeconds <= 0) {
            return;
        }
        List<Map<String, Object>> staleRows;
        if (projectId == null) {
            staleRows = jdbc.queryForList("""
                    SELECT id, project_id, worker, last_heartbeat_at
                    FROM intents
                    WHERE to_fact_id IS NULL
                      AND worker IS NOT NULL
                      AND last_heartbeat_at IS NOT NULL
                      AND (strftime('%s', ?) - strftime('%s', last_heartbeat_at)) > ?
                    """, now(), timeoutSeconds);
        } else {
            staleRows = jdbc.queryForList("""
                    SELECT id, project_id, worker, last_heartbeat_at
                    FROM intents
                    WHERE project_id = ?
                      AND to_fact_id IS NULL
                      AND worker IS NOT NULL
                      AND last_heartbeat_at IS NOT NULL
                      AND (strftime('%s', ?) - strftime('%s', last_heartbeat_at)) > ?
                    """, projectId, now(), timeoutSeconds);
        }
        for (Map<String, Object> row : staleRows) {
            String staleProjectId = String.valueOf(row.get("project_id"));
            String intentId = String.valueOf(row.get("id"));
            String worker = String.valueOf(row.get("worker"));
            String heartbeatAt = String.valueOf(row.get("last_heartbeat_at"));
            markProjectRecoveryRequired(
                    staleProjectId,
                    worker,
                    "intent",
                    intentId,
                    "intent_lease_expired_recovery_required",
                    "intent heartbeat expired at " + heartbeatAt + "; manual recovery required");
        }
        int timeout = timeoutSeconds;
        String sql = """
                UPDATE intents
                SET worker = NULL
                WHERE to_fact_id IS NULL
                  AND worker IS NOT NULL
                  AND last_heartbeat_at IS NOT NULL
                  AND (strftime('%s', ?) - strftime('%s', last_heartbeat_at)) > ?
                """;
        if (projectId == null) {
            jdbc.update(sql, now(), timeout);
        } else {
            jdbc.update(sql.replace("WHERE ", "WHERE project_id = ? AND "), projectId, now(), timeout);
        }
    }

    private void expireReasonLeases(String projectId) {
        int timeoutSeconds = settings().reason_timeout();
        if (timeoutSeconds <= 0) {
            return;
        }
        List<Map<String, Object>> staleRows;
        if (projectId == null) {
            staleRows = jdbc.queryForList("""
                    SELECT id, reason_worker, reason_last_heartbeat_at
                    FROM projects
                    WHERE reason_worker IS NOT NULL
                      AND reason_last_heartbeat_at IS NOT NULL
                      AND (strftime('%s', ?) - strftime('%s', reason_last_heartbeat_at)) > ?
                    """, now(), timeoutSeconds);
        } else {
            staleRows = jdbc.queryForList("""
                    SELECT id, reason_worker, reason_last_heartbeat_at
                    FROM projects
                    WHERE id = ?
                      AND reason_worker IS NOT NULL
                      AND reason_last_heartbeat_at IS NOT NULL
                      AND (strftime('%s', ?) - strftime('%s', reason_last_heartbeat_at)) > ?
                    """, projectId, now(), timeoutSeconds);
        }
        for (Map<String, Object> row : staleRows) {
            String staleProjectId = String.valueOf(row.get("id"));
            String worker = String.valueOf(row.get("reason_worker"));
            String heartbeatAt = String.valueOf(row.get("reason_last_heartbeat_at"));
            markProjectRecoveryRequired(
                    staleProjectId,
                    worker,
                    "reason",
                    "reason",
                    "reason_lease_expired_recovery_required",
                    "reason heartbeat expired at " + heartbeatAt + "; manual recovery required");
        }
        int timeout = timeoutSeconds;
        String sql = """
                UPDATE projects
                SET reason_worker = NULL, reason_trigger = NULL, reason_started_at = NULL, reason_last_heartbeat_at = NULL
                WHERE reason_worker IS NOT NULL
                  AND reason_last_heartbeat_at IS NOT NULL
                  AND (strftime('%s', ?) - strftime('%s', reason_last_heartbeat_at)) > ?
                """;
        if (projectId == null) {
            jdbc.update(sql, now(), timeout);
        } else {
            jdbc.update(sql.replace("WHERE ", "WHERE id = ? AND "), projectId, now(), timeout);
        }
    }

    private void markProjectRecoveryRequired(String projectId, String worker, String scopeType, String scopeId, String eventType, String message) {
        String eventAt = now();
        ensureProjectHealth(projectId, eventAt);
        jdbc.update("UPDATE projects SET status = 'stopped' WHERE id = ? AND status = 'active'", projectId);
        jdbc.update("""
                UPDATE project_health
                SET health_status = 'recovery_required',
                    updated_at = ?
                WHERE project_id = ?
                """, eventAt, projectId);
        recordRuntimeEvent(projectId, scopeType, scopeId, firstNonBlank(worker, "r0re.runtime"),
                eventType,
                new HeartbeatRequest(firstNonBlank(worker, "r0re.runtime"), "recovery", "lease_expired", message, null, null, null, null),
                eventAt);
    }

    private AutomationRunResponse runAutomationJob(String jobId) {
        int exists = count("SELECT COUNT(*) FROM automation_jobs WHERE id = ?", jobId);
        if (exists == 0) {
            throw HttpErrors.notFound("Automation job not found");
        }
        String now = now();
        List<String> messages = new ArrayList<>();
        int scanned = 0;
        int findings = 0;
        switch (jobId) {
            case "scan_conflicting_completions" -> {
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT project_id, COUNT(*) AS goal_count
                        FROM intents
                        WHERE to_fact_id = 'goal'
                        GROUP BY project_id
                        HAVING COUNT(*) > 1
                        """);
                scanned = count("SELECT COUNT(*) FROM projects");
                findings = rows.size();
                for (Map<String, Object> row : rows) {
                    String projectId = String.valueOf(row.get("project_id"));
                    String message = "conflicting goal completions count=" + row.get("goal_count");
                    markNeedsReview(projectId, message, now);
                    messages.add(projectId + ": " + message);
                }
            }
            case "scan_mock_pollution" -> {
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT DISTINCT project_id
                        FROM intents
                        WHERE to_fact_id = 'goal' AND lower(coalesce(worker, creator, '')) LIKE '%mock%'
                        """);
                scanned = count("SELECT COUNT(*) FROM projects");
                findings = rows.size();
                for (Map<String, Object> row : rows) {
                    String projectId = String.valueOf(row.get("project_id"));
                    String message = "mock-produced goal completion detected";
                    markNeedsReview(projectId, message, now);
                    messages.add(projectId + ": " + message);
                }
            }
            case "scan_stuck_candidate_pending" -> {
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT project_id, id
                        FROM completion_candidates
                        WHERE verification_status IN ('pending', 'verifying')
                          AND (strftime('%s', ?) - strftime('%s', created_at)) > 900
                        """, now);
                scanned = count("SELECT COUNT(*) FROM completion_candidates");
                findings = rows.size();
                for (Map<String, Object> row : rows) {
                    String projectId = String.valueOf(row.get("project_id"));
                    String candidateId = String.valueOf(row.get("id"));
                    String message = "stuck completion candidate " + candidateId;
                    markNeedsReview(projectId, message, now);
                    messages.add(projectId + ": " + message);
                }
            }
            case "retry_verifier_retryable_failed" -> {
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT project_id, id
                        FROM completion_candidates
                        WHERE verification_status = 'retryable_failed'
                        ORDER BY created_at
                        """);
                scanned = rows.size();
                for (Map<String, Object> row : rows) {
                    String projectId = String.valueOf(row.get("project_id"));
                    String candidateId = String.valueOf(row.get("id"));
                    int failureCount = verificationFailureCount(projectId, candidateId);
                    if (failureCount >= 3) {
                        messages.add(projectId + ": candidate " + candidateId + " exceeded retry budget");
                        continue;
                    }
                    rerunVerifier(projectId, new RerunVerifierRequest(candidateId, "r0re.automation"));
                    findings += 1;
                    messages.add(projectId + ": verifier rerun queued for " + candidateId);
                }
            }
            case "scan_dispatcher_health" -> {
                scanned = 1;
                findings = inspectAndRepairRuntimeTopology(messages);
            }
            case "scan_runtime_schema_mismatch" -> {
                scanned = 1;
                int healthRows = count("SELECT COUNT(*) FROM project_health");
                int projectRows = count("SELECT COUNT(*) FROM projects");
                if (healthRows < projectRows) {
                    findings = projectRows - healthRows;
                    messages.add("project_health missing rows=" + findings);
                    jdbc.update("""
                            INSERT OR IGNORE INTO project_health(project_id, health_status, completion_status, updated_at)
                            SELECT id, 'normal', CASE WHEN status = 'completed' THEN 'finalized' ELSE 'none' END, ?
                            FROM projects
                            """, now);
                } else {
                    messages.add("runtime schema ok");
                }
            }
            default -> throw HttpErrors.notFound("Automation job not found");
        }
        String status = findings > 0 ? "findings" : "ok";
        String message = messages.isEmpty() ? "no findings" : String.join("; ", messages);
        jdbc.update("""
                UPDATE automation_jobs
                SET last_run_at = ?, last_status = ?, last_message = ?
                WHERE id = ?
                """, now, status, trimSummary(message, 1000), jobId);
        return new AutomationRunResponse(status, scanned, findings, messages);
    }

    private void markNeedsReview(String projectId, String reason, String now) {
        ensureProjectHealth(projectId, now);
        jdbc.update("""
                UPDATE project_health
                SET health_status = 'needs_review',
                    quarantine_reason = ?,
                    updated_at = ?
                WHERE project_id = ?
                """, reason, now, projectId);
        recordRuntimeEvent(projectId, "project", projectId, "r0re.automation", "automation_needs_review",
                new HeartbeatRequest("r0re.automation", "automation", "scan", reason, null, null, null, null), now);
    }

    private int inspectAndRepairRuntimeTopology(List<String> messages) {
        Path rootDir = Path.of(System.getProperty("user.dir"));
        Path outputDir = rootDir.resolve("output");
        Path lockDir = outputDir.resolve("locks");
        int findings = 0;
        findings += cleanupStalePidFile(outputDir.resolve("r0re-serve.pid"), "serve", messages);
        findings += cleanupStalePidFile(outputDir.resolve("r0re-dispatch.pid"), "dispatch", messages);
        findings += cleanupStaleLockFiles(lockDir, messages);
        findings += detectDispatcherCoverage(lockDir, messages);
        if (findings == 0) {
            messages.add("runtime topology healthy");
        }
        return findings;
    }

    private int cleanupStalePidFile(Path pidFile, String name, List<String> messages) {
        String pid = readPidValue(pidFile);
        if (pid == null) {
            return 0;
        }
        if (isLivePid(pid)) {
            return 0;
        }
        try {
            Files.deleteIfExists(pidFile);
            messages.add(name + " stale pid file removed: " + pidFile.getFileName());
            return 1;
        } catch (IOException e) {
            messages.add(name + " stale pid file could not be removed: " + pidFile.getFileName());
            return 1;
        }
    }

    private int cleanupStaleLockFiles(Path lockDir, List<String> messages) {
        if (!Files.isDirectory(lockDir)) {
            return 0;
        }
        int findings = 0;
        try (var stream = Files.list(lockDir)) {
            for (Path lock : stream.filter(Files::isRegularFile).sorted().toList()) {
                String pid = readPidValue(lock);
                if (pid == null || isLivePid(pid)) {
                    continue;
                }
                findings += 1;
                try {
                    Files.deleteIfExists(lock);
                    messages.add("stale lock removed: " + lock.getFileName());
                } catch (IOException e) {
                    messages.add("stale lock could not be removed: " + lock.getFileName());
                }
            }
        } catch (IOException e) {
            messages.add("lock scan failed: " + e.getMessage());
            return 1;
        }
        return findings;
    }

    private int detectDispatcherCoverage(Path lockDir, List<String> messages) {
        int activeProjects = count("SELECT COUNT(*) FROM projects WHERE status = 'active'");
        if (activeProjects == 0) {
            return 0;
        }
        if (!Files.isDirectory(lockDir)) {
            messages.add("no lock directory while active projects exist");
            return 1;
        }
        try (var stream = Files.list(lockDir)) {
            long liveDispatchers = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("r0re-dispatch-"))
                    .map(this::readPidValue)
                    .filter(Objects::nonNull)
                    .filter(this::isLivePid)
                    .count();
            if (liveDispatchers == 0) {
                messages.add("no live dispatcher lock while active projects exist");
                return 1;
            }
            return 0;
        } catch (IOException e) {
            messages.add("dispatcher lock scan failed: " + e.getMessage());
            return 1;
        }
    }

    private String readPidValue(Path path) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(path)) {
                if (line.startsWith("pid=")) {
                    String value = line.substring(4).trim();
                    return value.isBlank() ? null : value;
                }
            }
            String value = Files.readString(path).trim();
            if (value.isBlank()) {
                return null;
            }
            return value.lines().findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean isLivePid(String pid) {
        try {
            long value = Long.parseLong(pid);
            return ProcessHandle.of(value).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureAutomationJobs() {
        defaultAutomationSchedules().forEach((jobId, schedule) -> {
            jdbc.update("INSERT OR IGNORE INTO automation_jobs(id, job_type, enabled, schedule) VALUES (?, ?, 1, ?)",
                    jobId, jobId, schedule);
            jdbc.update("""
                    UPDATE automation_jobs
                    SET schedule = ?
                    WHERE id = ? AND (schedule IS NULL OR schedule = '' OR schedule = 'manual')
                    """, schedule, jobId);
        });
    }

    private boolean isAutomationJobDue(AutomationJob job, String now) {
        long intervalSeconds = automationIntervalSeconds(job.schedule());
        if (intervalSeconds <= 0) {
            return false;
        }
        if (job.last_run_at() == null || job.last_run_at().isBlank()) {
            return true;
        }
        try {
            Instant lastRun = Instant.parse(job.last_run_at());
            Instant current = Instant.parse(now);
            return lastRun.plusSeconds(intervalSeconds).isBefore(current) || lastRun.plusSeconds(intervalSeconds).equals(current);
        } catch (Exception ignored) {
            return true;
        }
    }

    private long automationIntervalSeconds(String schedule) {
        if (schedule == null) {
            return -1;
        }
        String value = schedule.trim().toLowerCase();
        if (value.isEmpty() || "manual".equals(value)) {
            return -1;
        }
        if (value.startsWith("every:")) {
            try {
                return Math.max(1, Long.parseLong(value.substring("every:".length()).trim()));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private Map<String, String> defaultAutomationSchedules() {
        Map<String, String> schedules = new LinkedHashMap<>();
        schedules.put("scan_conflicting_completions", "every:300");
        schedules.put("scan_mock_pollution", "every:300");
        schedules.put("scan_stuck_candidate_pending", "every:180");
        schedules.put("retry_verifier_retryable_failed", "every:180");
        schedules.put("scan_dispatcher_health", "every:120");
        schedules.put("scan_runtime_schema_mismatch", "every:600");
        return schedules;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String nextProjectId() {
        jdbc.update("UPDATE counters SET value = value + 1 WHERE name = 'project'");
        Integer value = jdbc.queryForObject("SELECT value FROM counters WHERE name = 'project'", Integer.class);
        return "proj_%03d".formatted(value);
    }

    private String nextScopedId(String kind, String prefix, String projectId) {
        jdbc.update("INSERT OR IGNORE INTO scoped_counters (project_id, kind, value) VALUES (?, ?, 0)", projectId, kind);
        jdbc.update("UPDATE scoped_counters SET value = value + 1 WHERE project_id = ? AND kind = ?", projectId, kind);
        Integer value = jdbc.queryForObject("SELECT value FROM scoped_counters WHERE project_id = ? AND kind = ?",
                Integer.class, projectId, kind);
        return "%s%03d".formatted(prefix, value);
    }

    private ProjectRow requireProject(String projectId) {
        var rows = jdbc.query("SELECT * FROM projects WHERE id = ?", projectRowMapper(), projectId);
        if (rows.isEmpty()) {
            throw HttpErrors.notFound("Project not found");
        }
        return rows.get(0);
    }

    private void checkProjectActive(String projectId) {
        var row = requireProject(projectId);
        if (!row.status().equals("active")) {
            throw HttpErrors.forbidden("Project is " + row.status());
        }
    }

    private void validateFacts(String projectId, List<String> facts) {
        for (String fact : facts) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM facts WHERE id = ? AND project_id = ?",
                    Integer.class, fact, projectId);
            if (count == null || count == 0) {
                throw HttpErrors.notFound("Fact " + fact + " not found");
            }
        }
    }

    private IntentRow openIntent(String projectId, String intentId) {
        expireWorkers(projectId);
        var rows = jdbc.query("SELECT * FROM intents WHERE id = ? AND project_id = ?", intentRowMapper(), intentId, projectId);
        if (rows.isEmpty()) {
            throw HttpErrors.notFound("Intent not found");
        }
        var row = rows.get(0);
        if (row.toFactId() != null) {
            throw HttpErrors.conflict("Intent already concluded");
        }
        return row;
    }

    private void claimableIntent(String projectId, String intentId, String worker) {
        var row = openIntent(projectId, intentId);
        if (row.worker() != null && !row.worker().equals(worker.trim())) {
            throw HttpErrors.conflict("Intent is currently claimed by " + row.worker());
        }
    }

    private ProjectMeta projectMeta(String projectId) {
        var row = requireProject(projectId);
        return new ProjectMeta(row.id(), row.title(), row.status(), row.bootstrapEnabled(), row.createdAt(),
                row.reasonWorker() == null ? null : new ProjectReason(row.reasonWorker(), row.reasonTrigger(), row.reasonStartedAt(), row.reasonLastHeartbeatAt()));
    }

    private Intent intent(String intentId, String projectId) {
        return jdbc.queryForObject("SELECT * FROM intents WHERE id = ? AND project_id = ?", intentMapper(projectId), intentId, projectId);
    }

    private CompletionCandidate completionCandidate(String candidateId, String projectId) {
        var rows = jdbc.query("""
                SELECT * FROM completion_candidates
                WHERE id = ? AND project_id = ?
                """, completionCandidateMapper(), candidateId, projectId);
        if (rows.isEmpty()) {
            throw HttpErrors.notFound("Completion candidate not found");
        }
        return rows.get(0);
    }

    private List<String> candidateSources(CompletionCandidate candidate) {
        if (candidate.evidence_summary() == null || candidate.evidence_summary().isBlank()) {
            throw HttpErrors.conflict("Completion candidate has no source facts");
        }
        List<String> sources = java.util.Arrays.stream(candidate.evidence_summary().split(","))
                .map(String::trim)
                .filter(source -> !source.isEmpty())
                .distinct()
                .toList();
        if (sources.isEmpty()) {
            throw HttpErrors.conflict("Completion candidate has no source facts");
        }
        return sources;
    }

    private void ensureProjectHealth(String projectId, String updatedAt) {
        jdbc.update("""
                INSERT OR IGNORE INTO project_health(project_id, health_status, completion_status, updated_at)
                VALUES (?, 'normal',
                    CASE WHEN (SELECT status FROM projects WHERE id = ?) = 'completed' THEN 'finalized' ELSE 'none' END,
                    ?)
                """, projectId, projectId, updatedAt);
    }

    private void ensureWorkspaceSyncSetting() {
        try {
            jdbc.execute("ALTER TABLE settings ADD COLUMN workspace_sync_enabled INTEGER NOT NULL DEFAULT 1");
        } catch (Exception ignored) {
            // Existing databases may already have the column.
        }
        jdbc.update("""
                UPDATE settings
                SET workspace_sync_enabled = COALESCE(workspace_sync_enabled, 1)
                WHERE rowid = 1
                """);
    }

    private RuntimeScope scopeFromIntent(Intent intent, RuntimeEvent event, int timeoutSeconds) {
        return new RuntimeScope(
                "intent",
                intent.id(),
                intent.worker(),
                firstNonBlank(event == null ? null : event.phase(), intent.description()),
                event == null ? null : event.step(),
                event == null ? null : event.message(),
                event == null ? null : event.artifact(),
                event == null ? null : event.tool(),
                event == null ? null : event.progress_current(),
                event == null ? null : event.progress_total(),
                intent.last_heartbeat_at(),
                event == null ? null : event.created_at(),
                event == null ? null : event.event_type(),
                isStalled(intent.last_heartbeat_at(), timeoutSeconds)
        );
    }

    private RuntimeScope scopeFromReason(ProjectReason reason, RuntimeEvent event, int timeoutSeconds) {
        return new RuntimeScope(
                "reason",
                "reason",
                reason.worker(),
                firstNonBlank(event == null ? null : event.phase(), reason.trigger()),
                event == null ? null : event.step(),
                event == null ? null : event.message(),
                event == null ? null : event.artifact(),
                event == null ? null : event.tool(),
                event == null ? null : event.progress_current(),
                event == null ? null : event.progress_total(),
                reason.last_heartbeat_at(),
                event == null ? null : event.created_at(),
                event == null ? null : event.event_type(),
                isStalled(reason.last_heartbeat_at(), timeoutSeconds)
        );
    }

    private RuntimeScope scopeFromVerification(VerificationRecord record, int timeoutSeconds) {
        String heartbeatAt = firstNonBlank(record.latest_event_at(), record.verified_at());
        return new RuntimeScope(
                "verification",
                record.candidate_id(),
                record.verifier_worker(),
                firstNonBlank(record.verifier_phase(), record.verification_status()),
                firstNonBlank(record.verifier_prompt_group(), record.latest_event_type()),
                record.latest_output(),
                record.latest_artifact(),
                record.verifier_tool(),
                null,
                null,
                heartbeatAt,
                record.latest_event_at(),
                record.latest_event_type(),
                "verifying".equals(record.verification_status()) && isStalled(heartbeatAt, timeoutSeconds)
        );
    }

    private RuntimeEvent laterEvent(RuntimeEvent left, RuntimeEvent right) {
        return left.id() >= right.id() ? left : right;
    }

    private int verificationFailureCount(String projectId, String candidateId) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM runtime_events
                        WHERE project_id = ?
                          AND event_type = 'completion_candidate_verification_failed'
                          AND message LIKE ?
                        """,
                Integer.class,
                projectId,
                "%" + candidateId + "%");
        return count == null ? 0 : count;
    }

    private void ensureVerificationIntentSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS verification_intents (
                    id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                    candidate_id TEXT NOT NULL REFERENCES completion_candidates(id) ON DELETE CASCADE,
                    status TEXT NOT NULL,
                    worker TEXT,
                    prompt_group TEXT,
                    tool TEXT,
                    summary TEXT,
                    artifact TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_verification_intents_project_candidate
                ON verification_intents(project_id, candidate_id)
                """);
    }

    private void ensureVerificationIntents(String projectId) {
        ensureVerificationIntentSchema();
        jdbc.update("""
                INSERT OR IGNORE INTO verification_intents(id, project_id, candidate_id, status, worker, prompt_group, tool, summary, artifact, created_at, updated_at)
                SELECT 'v-' || id, project_id, id, verification_status, verifier_worker, NULL, NULL, NULL, artifact_path, created_at, COALESCE(verified_at, created_at)
                FROM completion_candidates
                WHERE project_id = ?
                """, projectId);
    }

    private void upsertVerificationIntent(String projectId, String candidateId, String status, String worker,
                                          String promptGroup, String tool, String summary, String artifact,
                                          String createdAt, String updatedAt) {
        ensureVerificationIntentSchema();
        jdbc.update("""
                INSERT INTO verification_intents(id, project_id, candidate_id, status, worker, prompt_group, tool, summary, artifact, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    status = excluded.status,
                    worker = excluded.worker,
                    prompt_group = COALESCE(excluded.prompt_group, verification_intents.prompt_group),
                    tool = COALESCE(excluded.tool, verification_intents.tool),
                    summary = COALESCE(excluded.summary, verification_intents.summary),
                    artifact = COALESCE(excluded.artifact, verification_intents.artifact),
                    updated_at = excluded.updated_at
                """,
                "v-" + candidateId,
                projectId,
                candidateId,
                status,
                blankToNull(worker),
                blankToNull(promptGroup),
                blankToNull(tool),
                blankToNull(summary),
                blankToNull(artifact),
                createdAt,
                updatedAt
        );
    }

    private VerificationIntent verificationIntent(String projectId, String candidateId) {
        ensureVerificationIntents(projectId);
        List<VerificationIntent> rows = jdbc.query("""
                SELECT * FROM verification_intents
                WHERE project_id = ? AND candidate_id = ?
                ORDER BY updated_at DESC
                LIMIT 1
                """, verificationIntentMapper(), projectId, candidateId);
        if (rows.isEmpty()) {
            return new VerificationIntent("v-" + candidateId, candidateId, "pending", null, null, null, null, null, null, null);
        }
        return rows.get(0);
    }

    private VerificationRecord verificationRecord(CompletionCandidate candidate, List<RuntimeEvent> events) {
        RuntimeEvent latest = latestVerificationEvent(candidate, events);
        VerificationIntent verificationIntent = verificationIntent(candidate.project_id(), candidate.id());
        return new VerificationRecord(
                candidate.id(),
                candidate.candidate_value(),
                candidate.verification_status(),
                verificationFailureCount(candidate.project_id(), candidate.id()),
                firstNonBlank(candidate.verifier_worker(), verificationIntent.worker()),
                latest == null ? null : latest.phase(),
                firstNonBlank(latest == null ? null : latest.step(), verificationIntent.prompt_group()),
                firstNonBlank(latest == null ? null : latest.tool(), verificationIntent.tool()),
                latest == null ? null : latest.event_type(),
                firstNonBlank(latest == null ? null : latest.message(), verificationIntent.summary()),
                firstNonBlank(latest == null ? null : latest.artifact(), verificationIntent.artifact()),
                candidate.verified_at(),
                firstNonBlank(latest == null ? null : latest.created_at(), verificationIntent.updated_at())
        );
    }

    private RuntimeEvent latestVerificationEvent(CompletionCandidate candidate, List<RuntimeEvent> events) {
        if (candidate == null || candidate.id() == null || events == null) {
            return null;
        }
        return events.stream()
                .filter(event -> "project".equals(event.scope_type()))
                .filter(event -> "verify".equals(event.phase()))
                .filter(event -> {
                    if (candidate.verifier_worker() != null && !candidate.verifier_worker().isBlank()) {
                        return candidate.verifier_worker().equals(event.worker());
                    }
                    return true;
                })
                .filter(event -> {
                    String message = event.message() == null ? "" : event.message();
                    return message.contains(candidate.id());
                })
                .max(Comparator.comparing(RuntimeEvent::id))
                .orElse(null);
    }

    private PhaseRuntimeState phaseRuntimeState(RuntimeScope scope, List<RuntimeEvent> recentEvents) {
        RuntimeEvent progressEvent = latestScopeEvent(scope, recentEvents, "task_progress_detected");
        RuntimeEvent stopConditionEvent = latestScopeEvent(scope, recentEvents, "task_stop_condition_met", "task_stop_condition_progress");
        RuntimeEvent recoveryEvent = latestScopeEvent(scope, recentEvents, "task_recovery_applied", "task_recovery_scheduled");
        return new PhaseRuntimeState(
                scope.scope_type(),
                scope.scope_id(),
                scope.phase(),
                artifactFocus(progressEvent),
                progressEvent == null ? null : firstNonBlank(progressEvent.message(), progressEvent.step()),
                stopConditionEvent == null ? null : firstNonBlank(stopConditionEvent.message(), stopConditionEvent.step()),
                recoveryEvent == null ? null : firstNonBlank(recoveryEvent.message(), recoveryEvent.step()),
                scope.stalled()
        );
    }

    private RuntimeEvent latestScopeEvent(RuntimeScope scope, List<RuntimeEvent> events, String... eventTypes) {
        return events.stream()
                .filter(event -> sameScope(scope, event))
                .filter(event -> {
                    if (eventTypes == null || eventTypes.length == 0) {
                        return true;
                    }
                    for (String eventType : eventTypes) {
                        if (eventType.equals(event.event_type())) {
                            return true;
                        }
                    }
                    return false;
                })
                .max(Comparator.comparing(RuntimeEvent::id))
                .orElse(null);
    }

    private boolean sameScope(RuntimeScope scope, RuntimeEvent event) {
        if (scope == null || event == null) {
            return false;
        }
        if (!scope.scope_type().equals(event.scope_type())) {
            return false;
        }
        String scopeId = scope.scope_id() == null ? "" : scope.scope_id();
        String eventScopeId = event.scope_id() == null ? "" : event.scope_id();
        return scopeId.equals(eventScopeId);
    }

    private String artifactFocus(RuntimeEvent progressEvent) {
        if (progressEvent == null || progressEvent.message() == null) {
            return null;
        }
        String message = progressEvent.message();
        int marker = message.indexOf("type=");
        if (marker < 0) {
            return null;
        }
        String tail = message.substring(marker + 5);
        int stop = tail.indexOf(' ');
        return stop >= 0 ? tail.substring(0, stop) : tail;
    }

    private boolean isStalled(String heartbeatAt, int timeoutSeconds) {
        if (heartbeatAt == null || heartbeatAt.isBlank()) {
            return false;
        }
        try {
            Instant heartbeat = Instant.parse(heartbeatAt);
            return heartbeat.plusSeconds(Math.max(1, timeoutSeconds)).isBefore(Instant.now());
        } catch (Exception ignored) {
            return false;
        }
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private String trimSummary(String value, int limit) {
        if (value == null) {
            return null;
        }
        int resolvedLimit = Math.max(1, limit);
        return value.length() <= resolvedLimit ? value : value.substring(0, resolvedLimit);
    }

    private void recordRuntimeEvent(String projectId, String scopeType, String scopeId, String worker, String eventType, HeartbeatRequest body, String createdAt) {
        insertRuntimeEvent(
                projectId,
                scopeType,
                scopeId,
                worker,
                eventType,
                body.phase(),
                body.step(),
                body.message(),
                body.artifact(),
                body.tool(),
                body.progress_current(),
                body.progress_total(),
                createdAt);
    }

    private void insertRuntimeEvent(String projectId, String scopeType, String scopeId, String worker, String eventType,
                                    String phase, String step, String message, String artifact, String tool,
                                    Integer progressCurrent, Integer progressTotal, String createdAt) {
        jdbc.update("""
                        INSERT INTO runtime_events (
                            project_id, scope_type, scope_id, worker, event_type, phase, step, message, artifact, tool,
                            progress_current, progress_total, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                projectId,
                blankToNull(scopeType),
                blankToNull(scopeId),
                blankToNull(worker),
                blankToNull(eventType),
                blankToNull(phase),
                blankToNull(step),
                blankToNull(message),
                blankToNull(artifact),
                blankToNull(tool),
                progressCurrent,
                progressTotal,
                createdAt);
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private RowMapper<Fact> factMapper() {
        return (rs, rowNum) -> new Fact(rs.getString("id"), rs.getString("description"),
                rs.getString("kind"), rs.getInt("verified") == 1, rs.getString("creator"));
    }

    private RowMapper<Hint> hintMapper() {
        return (rs, rowNum) -> new Hint(rs.getString("id"), rs.getString("content"), rs.getString("creator"), rs.getString("created_at"));
    }

    private RowMapper<Intent> intentMapper(String projectId) {
        return (rs, rowNum) -> {
            String intentId = rs.getString("id");
            List<String> from = jdbc.queryForList(
                    "SELECT fact_id FROM intent_sources WHERE intent_id = ? AND project_id = ? ORDER BY rowid",
                    String.class, intentId, projectId);
            return new Intent(intentId, from, rs.getString("to_fact_id"), rs.getString("description"),
                    rs.getString("creator"), rs.getString("worker"), rs.getString("last_heartbeat_at"),
                    rs.getString("created_at"), rs.getString("concluded_at"));
        };
    }

    private RowMapper<RuntimeEvent> runtimeEventMapper() {
        return (rs, rowNum) -> new RuntimeEvent(
                rs.getLong("id"),
                rs.getString("project_id"),
                rs.getString("scope_type"),
                rs.getString("scope_id"),
                rs.getString("worker"),
                rs.getString("event_type"),
                rs.getString("phase"),
                rs.getString("step"),
                rs.getString("message"),
                rs.getString("artifact"),
                rs.getString("tool"),
                integerOrNull(rs, "progress_current"),
                integerOrNull(rs, "progress_total"),
                rs.getString("created_at")
        );
    }

    private RowMapper<CompletionCandidate> completionCandidateMapper() {
        return (rs, rowNum) -> new CompletionCandidate(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("source_intent_id"),
                rs.getString("worker"),
                rs.getString("candidate_type"),
                rs.getString("candidate_value"),
                rs.getString("evidence_summary"),
                rs.getString("artifact_path"),
                rs.getString("verification_status"),
                rs.getString("verifier_worker"),
                rs.getString("verified_at"),
                rs.getString("created_at")
        );
    }

    private RowMapper<ProjectHealth> projectHealthMapper() {
        return (rs, rowNum) -> new ProjectHealth(
                rs.getString("project_id"),
                rs.getString("health_status"),
                rs.getString("completion_status"),
                rs.getString("active_dispatch_profile"),
                rs.getString("quarantine_reason"),
                rs.getString("updated_at")
        );
    }

    private RowMapper<VerificationIntent> verificationIntentMapper() {
        return (rs, rowNum) -> new VerificationIntent(
                rs.getString("id"),
                rs.getString("candidate_id"),
                rs.getString("status"),
                rs.getString("worker"),
                rs.getString("prompt_group"),
                rs.getString("tool"),
                rs.getString("summary"),
                rs.getString("artifact"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }

    private RowMapper<AutomationJob> automationJobMapper() {
        return (rs, rowNum) -> new AutomationJob(
                rs.getString("id"),
                rs.getString("job_type"),
                rs.getInt("enabled") != 0,
                rs.getString("schedule"),
                rs.getString("payload"),
                rs.getString("last_run_at"),
                rs.getString("last_status"),
                rs.getString("last_message")
        );
    }

    private Integer integerOrNull(ResultSet rs, String name) throws SQLException {
        int value = rs.getInt(name);
        return rs.wasNull() ? null : value;
    }

    private RowMapper<ProjectRow> projectRowMapper() {
        return (rs, rowNum) -> new ProjectRow(rs.getString("id"), rs.getString("title"), rs.getString("status"),
                rs.getInt("bootstrap_enabled") != 0, rs.getString("created_at"), rs.getString("reason_worker"),
                rs.getString("reason_trigger"), rs.getString("reason_started_at"), rs.getString("reason_last_heartbeat_at"));
    }

    private RowMapper<IntentRow> intentRowMapper() {
        return (rs, rowNum) -> new IntentRow(rs.getString("id"), rs.getString("to_fact_id"), rs.getString("worker"));
    }

    private ProjectReason reason(ResultSet rs) throws SQLException {
        String worker = rs.getString("reason_worker");
        if (worker == null) {
            return null;
        }
        return new ProjectReason(worker, rs.getString("reason_trigger"), rs.getString("reason_started_at"), rs.getString("reason_last_heartbeat_at"));
    }

    private record ProjectRow(String id, String title, String status, boolean bootstrapEnabled, String createdAt,
                              String reasonWorker, String reasonTrigger, String reasonStartedAt, String reasonLastHeartbeatAt) {
    }

    private record IntentRow(String id, String toFactId, String worker) {
    }
}
