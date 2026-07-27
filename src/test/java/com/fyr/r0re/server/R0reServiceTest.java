package com.fyr.r0re.server;

import com.fyr.r0re.protocol.Models.CompleteRequest;
import com.fyr.r0re.protocol.Models.CompletionCandidate;
import com.fyr.r0re.protocol.Models.CreateRuntimeEventRequest;
import com.fyr.r0re.protocol.Models.CreateHintRequest;
import com.fyr.r0re.protocol.Models.CreateProjectRequest;
import com.fyr.r0re.protocol.Models.FinalizeCompletionRequest;
import com.fyr.r0re.protocol.Models.QuarantineRequest;
import com.fyr.r0re.protocol.Models.RepairCompletionRequest;
import com.fyr.r0re.protocol.Models.RerunVerifierRequest;
import com.fyr.r0re.protocol.Models.ResumeExecutionRequest;
import com.fyr.r0re.protocol.Models.ReopenRequest;
import com.fyr.r0re.protocol.Models.VerifyCompletionRequest;
import com.fyr.r0re.config.R0reProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.JDBC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R0reServiceTest {
    private final String originalUserDir = System.getProperty("user.dir");
    private Path dbPath;
    private Path tempRoot;

    @AfterEach
    void tearDown() throws Exception {
        System.setProperty("user.dir", originalUserDir);
        if (dbPath != null) {
            Files.deleteIfExists(dbPath);
        }
        if (tempRoot != null && Files.exists(tempRoot)) {
            try (var walk = Files.walk(tempRoot)) {
                walk.sorted((a, b) -> b.compareTo(a))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        });
            }
        }
    }

    @Test
    void createHintReactivatesCompletedProject() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "done");
        jdbc.update("INSERT OR REPLACE INTO scoped_counters (project_id, kind, value) VALUES (?, ?, ?)", projectId, "fact", 1);
        jdbc.update("""
                UPDATE projects
                SET reason_worker = ?, reason_trigger = ?, reason_started_at = ?, reason_last_heartbeat_at = ?
                WHERE id = ?
                """, "pi_deepseek", "facts:2->3", "2026-06-17T00:00:00Z", "2026-06-17T00:00:00Z", projectId);
        insertLegacyCompletion(jdbc, projectId, "f001");

        service.createHint(projectId, new CreateHintRequest("re-check native branch", "Human"));

        var detail = service.getProject(projectId);
        assertEquals("active", detail.project().status());
        assertEquals(1, detail.hints().size());
        assertNull(detail.project().reason());
    }

    @Test
    void reopenReactivatesCompletedProjectAndRemovesCompletionIntent() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "old answer wrong");
        jdbc.update("INSERT OR REPLACE INTO scoped_counters (project_id, kind, value) VALUES (?, ?, ?)", projectId, "fact", 1);
        insertLegacyCompletion(jdbc, projectId, "f001");

        service.reopen(projectId, new ReopenRequest("WOJIUSHIDAAN is wrong, analyze native deeper", "Human"));

        var detail = service.getProject(projectId);
        assertEquals("active", detail.project().status());
        assertEquals(0, detail.intents().stream().filter(intent -> "goal".equals(intent.to())).count());
        assertEquals("external_feedback", detail.intents().get(detail.intents().size() - 1).description());
    }

    @Test
    void completeCreatesCandidateWithoutCompletingProject() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");

        CompletionCandidate candidate = service.complete(projectId, new CompleteRequest(List.of("f001"), "complete", "pi_deepseek"));

        var detail = service.getProject(projectId);
        assertEquals("active", detail.project().status());
        assertEquals("pending", candidate.verification_status());
        assertEquals("complete", candidate.candidate_value());
        assertEquals(1, service.completionCandidates(projectId).size());
        assertEquals("candidate_pending", service.projectHealth(projectId).completion_status());
    }

    @Test
    void finalizeRequiresApprovedCandidate() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");

        CompletionCandidate candidate = service.complete(projectId, new CompleteRequest(List.of("f001"), "complete", "pi_deepseek"));

        assertThrows(RuntimeException.class, () ->
                service.finalizeCompletion(projectId, candidate.id(), new FinalizeCompletionRequest("verifier")));
        assertEquals("active", service.getProject(projectId).project().status());
        assertEquals(0, service.getProject(projectId).intents().stream().filter(intent -> "goal".equals(intent.to())).count());
    }

    @Test
    void candidateCanEnterVerificationBeforeApproval() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");

        CompletionCandidate candidate = service.complete(projectId, new CompleteRequest(List.of("f001"), "complete", "pi_deepseek"));
        CompletionCandidate verifying = service.startCompletionVerification(projectId, candidate.id(), new VerifyCompletionRequest("r0re.rule-verifier", null, null));

        assertEquals("verifying", verifying.verification_status());
        assertEquals("r0re.rule-verifier", verifying.verifier_worker());
        assertEquals("verifying", service.projectHealth(projectId).completion_status());
        service.approveCompletionCandidate(projectId, candidate.id());
        assertEquals("approved", service.projectHealth(projectId).completion_status());
    }

    @Test
    void approvedCandidateCanBeFinalized() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "DemoApp2", "origin", "goal", true, List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");

        CompletionCandidate candidate = service.complete(projectId, new CompleteRequest(List.of("f001"), "complete", "pi_deepseek"));
        service.approveCompletionCandidate(projectId, candidate.id());
        service.finalizeCompletion(projectId, candidate.id(), new FinalizeCompletionRequest("verifier"));

        var detail = service.getProject(projectId);
        assertEquals("completed", detail.project().status());
        assertEquals(1, detail.intents().stream().filter(intent -> "goal".equals(intent.to())).count());
        assertEquals("finalized", service.completionCandidates(projectId).get(0).verification_status());
        assertEquals("finalized", service.projectHealth(projectId).completion_status());
    }

    @Test
    void reverseProjectCannotFinalizeWithoutVerifierEvidence() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "Android reverse crackme",
                "Reverse the APK native checker",
                "Recover the final flag",
                true,
                List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");

        CompletionCandidate candidate = service.complete(projectId, new CompleteRequest(List.of("f001"), "flag{demo}", "pi_deepseek"));
        service.startCompletionVerification(projectId, candidate.id(), new com.fyr.r0re.protocol.Models.VerifyCompletionRequest("reverse-verifier", null, null));
        service.approveCompletionCandidate(projectId, candidate.id());

        assertThrows(RuntimeException.class, () ->
                service.finalizeCompletion(projectId, candidate.id(), new FinalizeCompletionRequest("verifier")));
    }

    @Test
    void reverseProjectCanFinalizeWithVerifierEvidenceAndArtifact() throws Exception {
        JdbcTemplate jdbc = jdbc();
        tempRoot = Files.createTempDirectory("r0re-reverse-policy-test-");
        System.setProperty("user.dir", tempRoot.toString());
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "Android reverse crackme",
                "Reverse the APK native checker",
                "Recover the final flag",
                true,
                List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");

        Path artifactDir = tempRoot.resolve("output/live-analysis").resolve(projectId);
        Files.createDirectories(artifactDir);
        Files.writeString(artifactDir.resolve("solver-report.txt"), "passed\n");

        CompletionCandidate candidate = service.complete(projectId, new CompleteRequest(List.of("f001"), "flag{demo}", "pi_deepseek"));
        service.startCompletionVerification(projectId, candidate.id(), new com.fyr.r0re.protocol.Models.VerifyCompletionRequest("reverse-verifier", null, null));
        service.appendRuntimeEvent(projectId, new CreateRuntimeEventRequest(
                "project",
                projectId,
                "reverse-verifier",
                "completion_candidate_auto_approved",
                "verify",
                "rule_verifier",
                "passed solver verification",
                "solver-report.txt",
                "solver",
                null,
                null
        ));
        service.approveCompletionCandidate(projectId, candidate.id());
        service.finalizeCompletion(projectId, candidate.id(), new FinalizeCompletionRequest("verifier"));

        assertEquals("completed", service.getProject(projectId).project().status());
    }

    @Test
    void projectVerificationStateAggregatesLatestVerifierEvent() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "Android reverse crackme",
                "Reverse the APK native checker",
                "Recover the final flag",
                true,
                List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");

        CompletionCandidate candidate = service.complete(projectId, new CompleteRequest(List.of("f001"), "flag{demo}", "pi_deepseek"));
        service.startCompletionVerification(projectId, candidate.id(), new VerifyCompletionRequest("android-ctf-verifier", null, null));
        service.appendRuntimeEvent(projectId, new CreateRuntimeEventRequest(
                "project",
                projectId,
                "android-ctf-verifier",
                "completion_candidate_auto_approved",
                "verify",
                "android-ctf-verifier",
                "completion candidate " + candidate.id() + " passed rule verifier",
                "solver-report.txt",
                "rule-verifier",
                null,
                null
        ));
        service.approveCompletionCandidate(projectId, candidate.id());

        var state = service.projectVerificationState(projectId);

        assertEquals("approved", state.completion_status());
        assertEquals(1, state.records().size());
        assertEquals(candidate.id(), state.records().get(0).candidate_id());
        assertEquals("android-ctf-verifier", state.records().get(0).verifier_worker());
        assertEquals("android-ctf-verifier", state.records().get(0).verifier_prompt_group());
        assertEquals("rule-verifier", state.records().get(0).verifier_tool());
        assertEquals("completion candidate " + candidate.id() + " passed rule verifier", state.records().get(0).latest_output());
    }

    @Test
    void projectRuntimeIncludesVerificationScopes() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "Android reverse crackme",
                "Reverse the APK native checker",
                "Recover the final flag",
                true,
                List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");

        CompletionCandidate candidate = service.complete(projectId, new CompleteRequest(List.of("f001"), "flag{demo}", "pi_deepseek"));
        service.startCompletionVerification(projectId, candidate.id(), new VerifyCompletionRequest("android-ctf-verifier", null, null));
        service.appendRuntimeEvent(projectId, new CreateRuntimeEventRequest(
                "project",
                projectId,
                "android-ctf-verifier",
                "completion_candidate_verifying",
                "verify",
                "android-ctf-verifier",
                "completion candidate " + candidate.id() + " verification started",
                null,
                "rule-verifier",
                null,
                null
        ));

        var runtime = service.projectRuntime(projectId, 20);

        assertEquals(1, runtime.verifications().size());
        assertEquals("verification", runtime.verifications().get(0).scope_type());
        assertEquals(candidate.id(), runtime.verifications().get(0).scope_id());
        assertEquals("android-ctf-verifier", runtime.verifications().get(0).worker());
    }

    @Test
    void projectVerificationIntentsExposeDerivedVerifierObjects() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "Android reverse crackme",
                "Reverse the APK native checker",
                "Recover the final flag",
                true,
                List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");

        CompletionCandidate candidate = service.complete(projectId, new CompleteRequest(List.of("f001"), "flag{demo}", "pi_deepseek"));
        service.startCompletionVerification(projectId, candidate.id(), new VerifyCompletionRequest("android-ctf-verifier", null, "rule-verifier"));
        service.appendRuntimeEvent(projectId, new CreateRuntimeEventRequest(
                "project",
                projectId,
                "android-ctf-verifier",
                "completion_candidate_auto_approved",
                "verify",
                "android-ctf-verifier",
                "completion candidate " + candidate.id() + " passed rule verifier",
                "solver-report.txt",
                "rule-verifier",
                null,
                null
        ));
        service.approveCompletionCandidate(projectId, candidate.id());

        var intents = service.projectVerificationIntents(projectId);

        assertEquals(1, intents.size());
        assertEquals("v-" + candidate.id(), intents.get(0).id());
        assertEquals(candidate.id(), intents.get(0).candidate_id());
        assertEquals("approved", intents.get(0).status());
        assertEquals("android-ctf-verifier", intents.get(0).worker());
        assertEquals("rule-verifier", intents.get(0).tool());
    }

    @Test
    void finalizedCandidateCannotBeApprovedOrRejectedAgain() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "DemoApp2", "origin", "goal", true, List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");

        CompletionCandidate candidate = service.complete(projectId, new CompleteRequest(List.of("f001"), "complete", "pi_deepseek"));
        service.approveCompletionCandidate(projectId, candidate.id());
        service.finalizeCompletion(projectId, candidate.id(), new FinalizeCompletionRequest("verifier"));

        assertThrows(RuntimeException.class, () -> service.approveCompletionCandidate(projectId, candidate.id()));
        assertThrows(RuntimeException.class, () -> service.rejectCompletionCandidate(projectId, candidate.id()));
        assertEquals("completed", service.getProject(projectId).project().status());
    }

    @Test
    void rejectedCandidateMarksProjectNeedsReview() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");

        CompletionCandidate candidate = service.complete(projectId, new CompleteRequest(List.of("f001"), "complete", "pi_deepseek"));
        service.startCompletionVerification(projectId, candidate.id(), new VerifyCompletionRequest("r0re.rule-verifier", null, null));
        service.rejectCompletionCandidate(projectId, candidate.id());

        var health = service.projectHealth(projectId);
        assertEquals("needs_review", health.health_status());
        assertEquals("rejected", health.completion_status());
    }

    @Test
    void rerunVerifierResetsRejectedCandidateBackToPending() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");

        CompletionCandidate candidate = service.complete(projectId, new CompleteRequest(List.of("f001"), "complete", "pi_deepseek"));
        service.startCompletionVerification(projectId, candidate.id(), new VerifyCompletionRequest("r0re.rule-verifier", null, null));
        service.rejectCompletionCandidate(projectId, candidate.id());

        CompletionCandidate rerun = service.rerunVerifier(projectId, new RerunVerifierRequest(candidate.id(), "Human"));

        assertEquals("pending", rerun.verification_status());
        assertNull(rerun.verifier_worker());
        assertEquals("candidate_pending", service.projectHealth(projectId).completion_status());
        assertEquals("normal", service.projectHealth(projectId).health_status());
    }

    @Test
    void verifierFailureEntersRetryableFailedBeforeManualReview() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "Android reverse crackme",
                "Reverse the APK native checker",
                "Recover the final flag",
                true,
                List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");

        CompletionCandidate candidate = service.complete(projectId, new CompleteRequest(List.of("f001"), "flag{demo}", "pi_deepseek"));
        service.startCompletionVerification(projectId, candidate.id(), new VerifyCompletionRequest("android-ctf-verifier", null, null));

        CompletionCandidate failed = service.failCompletionVerification(projectId, candidate.id(), new com.fyr.r0re.protocol.Models.VerificationFailureRequest(
                "android-ctf-verifier",
                "temporary verifier outage",
                true
        ));

        assertEquals("retryable_failed", failed.verification_status());
        assertEquals("retryable_failed", service.projectHealth(projectId).completion_status());
        assertEquals("normal", service.projectHealth(projectId).health_status());
    }

    @Test
    void verifierFailureEscalatesToManualReviewAfterThreeFailures() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "Android reverse crackme",
                "Reverse the APK native checker",
                "Recover the final flag",
                true,
                List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");

        CompletionCandidate candidate = service.complete(projectId, new CompleteRequest(List.of("f001"), "flag{demo}", "pi_deepseek"));

        for (int i = 0; i < 3; i++) {
            service.startCompletionVerification(projectId, candidate.id(), new VerifyCompletionRequest("android-ctf-verifier", null, null));
            service.failCompletionVerification(projectId, candidate.id(), new com.fyr.r0re.protocol.Models.VerificationFailureRequest(
                    "android-ctf-verifier",
                    "verifier failed attempt " + (i + 1),
                    true
            ));
            if (i < 2) {
                service.rerunVerifier(projectId, new RerunVerifierRequest(candidate.id(), "Human"));
            }
        }

        CompletionCandidate latest = service.completionCandidates(projectId).get(0);
        assertEquals("manual_review", latest.verification_status());
        assertEquals("manual_review", service.projectHealth(projectId).completion_status());
        assertEquals("needs_review", service.projectHealth(projectId).health_status());
    }

    @Test
    void quarantineAndUnquarantineUpdateProjectHealth() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();

        service.quarantineProject(projectId, new QuarantineRequest("mock completion detected", "verifier"));
        assertEquals("quarantined", service.projectHealth(projectId).health_status());

        service.unquarantineProject(projectId, new QuarantineRequest("manual release", "Human"));
        assertEquals("normal", service.projectHealth(projectId).health_status());
        assertNull(service.projectHealth(projectId).quarantine_reason());
    }

    @Test
    void repairCompletionRemovesDuplicateGoalIntentsAndClearsReview() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");
        insertLegacyCompletion(jdbc, projectId, "f001");
        jdbc.update("""
                INSERT INTO intents (id, project_id, to_fact_id, description, creator, worker, last_heartbeat_at, created_at, concluded_at)
                VALUES ('i002', ?, 'goal', 'duplicate complete', 'mock', 'mock', '2026-06-17T00:01:00Z', '2026-06-17T00:01:00Z', '2026-06-17T00:01:00Z')
                """, projectId);
        jdbc.update("INSERT INTO intent_sources (intent_id, project_id, fact_id) VALUES ('i002', ?, ?)", projectId, "f001");
        jdbc.update("UPDATE project_health SET health_status = 'quarantined', quarantine_reason = 'duplicate goal' WHERE project_id = ?", projectId);

        var repair = service.repairCompletion(projectId, new RepairCompletionRequest("repair"));

        assertEquals(1, repair.removed_goal_intents());
        assertEquals("normal", repair.health().health_status());
        assertEquals(1, service.getProject(projectId).intents().stream().filter(intent -> "goal".equals(intent.to())).count());
    }

    @Test
    void automationScanMarksConflictingCompletionsForReview() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");
        insertLegacyCompletion(jdbc, projectId, "f001");
        jdbc.update("""
                INSERT INTO intents (id, project_id, to_fact_id, description, creator, worker, last_heartbeat_at, created_at, concluded_at)
                VALUES ('i002', ?, 'goal', 'duplicate complete', 'mock', 'mock', '2026-06-17T00:01:00Z', '2026-06-17T00:01:00Z', '2026-06-17T00:01:00Z')
                """, projectId);
        jdbc.update("INSERT INTO intent_sources (intent_id, project_id, fact_id) VALUES ('i002', ?, ?)", projectId, "f001");

        var result = service.runAutomation("scan_conflicting_completions");

        assertEquals(1, result.findings());
        assertEquals("needs_review", service.projectHealth(projectId).health_status());
    }

    @Test
    void automationJobsAreInitialized() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        assertEquals(6, service.automationJobs().size());
        assertEquals("every:300", service.automationJobs().stream()
                .filter(job -> "scan_conflicting_completions".equals(job.id()))
                .findFirst()
                .orElseThrow()
                .schedule());
    }

    @Test
    void dueAutomationJobsRespectScheduleAndLastRunTime() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        jdbc.update("UPDATE automation_jobs SET last_run_at = ? WHERE id = ?",
                "2099-01-01T00:00:00Z", "scan_conflicting_completions");
        jdbc.update("UPDATE automation_jobs SET enabled = 0 WHERE id = ?",
                "scan_mock_pollution");

        var due = service.dueAutomationJobs();

        assertEquals(4, due.size());
        assertEquals(0, due.stream().filter(job -> "scan_conflicting_completions".equals(job.id())).count());
        assertEquals(0, due.stream().filter(job -> "scan_mock_pollution".equals(job.id())).count());
    }

    @Test
    void automationJobScheduleBackfillsLegacyManualRows() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        jdbc.update("UPDATE automation_jobs SET schedule = 'manual' WHERE id = ?",
                "scan_runtime_schema_mismatch");

        var jobs = service.automationJobs();

        assertEquals("every:600", jobs.stream()
                .filter(job -> "scan_runtime_schema_mismatch".equals(job.id()))
                .findFirst()
                .orElseThrow()
                .schedule());
    }

    @Test
    void automationRetryVerifierJobRerunsRetryableFailedCandidates() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "Android reverse crackme",
                "Reverse the APK native checker",
                "Recover the final flag",
                true,
                List.of()
        )).project().id();
        jdbc.update("INSERT INTO facts (id, project_id, description) VALUES (?, ?, ?)", "f001", projectId, "candidate answer");

        CompletionCandidate candidate = service.complete(projectId, new CompleteRequest(List.of("f001"), "flag{demo}", "pi_deepseek"));
        service.startCompletionVerification(projectId, candidate.id(), new VerifyCompletionRequest("android-ctf-verifier", null, null));
        service.failCompletionVerification(projectId, candidate.id(), new com.fyr.r0re.protocol.Models.VerificationFailureRequest(
                "android-ctf-verifier",
                "temporary verifier outage",
                true
        ));

        var result = service.runAutomation("retry_verifier_retryable_failed");

        assertEquals(1, result.findings());
        assertEquals("pending", service.completionCandidates(projectId).get(0).verification_status());
        assertEquals("candidate_pending", service.projectHealth(projectId).completion_status());
    }

    @Test
    void automationDispatcherHealthRepairsStaleArtifacts() throws Exception {
        JdbcTemplate jdbc = jdbc();
        tempRoot = Files.createTempDirectory("r0re-runtime-topology-test-");
        System.setProperty("user.dir", tempRoot.toString());
        R0reService service = new R0reService(jdbc);

        Files.createDirectories(tempRoot.resolve("output/locks"));
        Files.writeString(tempRoot.resolve("output/r0re-dispatch.pid"), "999998\n");
        Files.writeString(tempRoot.resolve("output/locks/r0re-dispatch-test.lock"), "pid=999998\nprofile=test\n");

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();

        var result = service.runAutomation("scan_dispatcher_health");

        assertEquals(3, result.findings());
        assertFalse(Files.exists(tempRoot.resolve("output/r0re-dispatch.pid")));
        assertFalse(Files.exists(tempRoot.resolve("output/locks/r0re-dispatch-test.lock")));
        assertTrue(result.messages().stream().anyMatch(message -> message.contains("stale pid file removed")));
        assertTrue(result.messages().stream().anyMatch(message -> message.contains("stale lock removed")));
        assertTrue(result.messages().stream().anyMatch(message -> message.contains("no live dispatcher lock")));
        assertEquals("active", service.getProject(projectId).project().status());
    }

    @Test
    void projectRuntimeIncludesSchemaVersion() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();

        var runtime = service.projectRuntime(projectId, 20);

        assertEquals("harness-loop.v1", runtime.schema_version());
    }

    @Test
    void updateStatusActiveClearsOutstandingIntentAndReasonLeases() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();
        jdbc.update("""
                INSERT INTO intents (id, project_id, to_fact_id, description, creator, worker, last_heartbeat_at, created_at, concluded_at)
                VALUES (?, ?, NULL, ?, ?, ?, ?, ?, NULL)
                """, "i001", projectId, "bootstrap", "dispatcher.bootstrap", "pi_glm_aiproxy", "2026-06-18T00:00:00Z", "2026-06-18T00:00:00Z");
        jdbc.update("""
                UPDATE projects
                SET status = 'stopped', reason_worker = ?, reason_trigger = ?, reason_started_at = ?, reason_last_heartbeat_at = ?
                WHERE id = ?
                """, "pi_glm_aiproxy", "initial", "2026-06-18T00:00:00Z", "2026-06-18T00:00:00Z", projectId);

        service.updateStatus(projectId, new com.fyr.r0re.protocol.Models.UpdateProjectStatusRequest("active"));

        var detail = service.getProject(projectId);
        assertEquals("active", detail.project().status());
        assertNull(detail.project().reason());
        assertNull(detail.intents().get(0).worker());
        assertNull(detail.intents().get(0).last_heartbeat_at());
    }

    @Test
    void updateStatusActiveRecordsWakeEventWhenDispatcherAlreadyRunning() throws Exception {
        tempRoot = Files.createTempDirectory("r0re-live-dispatcher-wake-test-");
        System.setProperty("user.dir", tempRoot.toString());
        Files.createDirectories(tempRoot.resolve("output/locks"));
        Files.writeString(tempRoot.resolve("output/locks/r0re-dispatch-test.lock"),
                "pid=" + ProcessHandle.current().pid() + "\nconfig=dispatch.kimi.swarm.yaml\n");

        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();
        jdbc.update("UPDATE projects SET status = 'stopped' WHERE id = ?", projectId);

        service.updateStatus(projectId, new com.fyr.r0re.protocol.Models.UpdateProjectStatusRequest("active"));

        assertTrue(service.projectRuntime(projectId, 20).recent_events().stream()
                .anyMatch(event -> "dispatcher_wakeup_requested".equals(event.event_type())
                        && "update_status_active".equals(event.step())));
    }

    @Test
    void resumeExecutionRecoversActiveProjectWithStaleLeases() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();
        jdbc.update("""
                INSERT INTO intents (id, project_id, to_fact_id, description, creator, worker, last_heartbeat_at, created_at, concluded_at)
                VALUES (?, ?, NULL, ?, ?, ?, ?, ?, NULL)
                """, "i001", projectId, "bootstrap", "dispatcher.bootstrap", "pi_glm_aiproxy", "2026-06-18T00:00:00Z", "2026-06-18T00:00:00Z");
        jdbc.update("""
                UPDATE projects
                SET status = 'active', reason_worker = ?, reason_trigger = ?, reason_started_at = ?, reason_last_heartbeat_at = ?
                WHERE id = ?
                """, "pi_glm_aiproxy", "facts:1->2", "2026-06-18T00:00:00Z", "2026-06-18T00:00:00Z", projectId);

        service.resumeExecution(projectId, new ResumeExecutionRequest("WebUI"));

        var detail = service.getProject(projectId);
        assertEquals("active", detail.project().status());
        assertNull(detail.project().reason());
        assertNull(detail.intents().get(0).worker());
        assertNull(detail.intents().get(0).last_heartbeat_at());
        assertTrue(service.projectRuntime(projectId, 20).recent_events().stream()
                .anyMatch(event -> "project_execution_resumed".equals(event.event_type())));
    }

    @Test
    void serveStartupStopsPersistedActiveProjectsAndClearsLeases() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();
        jdbc.update("""
                INSERT INTO intents (id, project_id, to_fact_id, description, creator, worker, last_heartbeat_at, created_at, concluded_at)
                VALUES (?, ?, NULL, ?, ?, ?, ?, ?, NULL)
                """, "i001", projectId, "bootstrap", "dispatcher.bootstrap", "kimi_swarm", "2026-07-23T00:00:00Z", "2026-07-23T00:00:00Z");
        jdbc.update("""
                UPDATE projects
                SET reason_worker = ?, reason_trigger = ?, reason_started_at = ?, reason_last_heartbeat_at = ?
                WHERE id = ?
                """, "kimi_swarm", "initial", "2026-07-23T00:00:00Z", "2026-07-23T00:00:00Z", projectId);

        new ServeStartupProjectState(jdbc, new R0reProperties()).run(null);

        var detail = service.getProject(projectId);
        assertEquals("stopped", detail.project().status());
        assertNull(detail.project().reason());
        assertNull(detail.intents().get(0).worker());
        assertNull(detail.intents().get(0).last_heartbeat_at());
        assertTrue(service.projectRuntime(projectId, 20).recent_events().stream()
                .anyMatch(event -> "project_startup_stopped".equals(event.event_type())));
    }

    @Test
    void dispatchStartupDoesNotStopActiveProjects() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);

        String projectId = service.createProject(new CreateProjectRequest(
                "AliCrackme2", "origin", "goal", true, List.of()
        )).project().id();
        R0reProperties properties = new R0reProperties();
        properties.getDispatcher().setEnabled(true);

        new ServeStartupProjectState(jdbc, properties).run(null);

        assertEquals("active", service.getProject(projectId).project().status());
    }

    private JdbcTemplate jdbc() throws Exception {
        dbPath = Files.createTempFile("r0re-service-test-", ".sqlite");
        String url = "jdbc:sqlite:" + dbPath;
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new JDBC(), url);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        return new JdbcTemplate(dataSource);
    }

    private void insertLegacyCompletion(JdbcTemplate jdbc, String projectId, String sourceFactId) {
        jdbc.update("""
                INSERT INTO intents (id, project_id, to_fact_id, description, creator, worker, last_heartbeat_at, created_at, concluded_at)
                VALUES ('i001', ?, 'goal', 'complete', 'pi_deepseek', 'pi_deepseek', '2026-06-17T00:00:00Z', '2026-06-17T00:00:00Z', '2026-06-17T00:00:00Z')
                """, projectId);
        jdbc.update("INSERT INTO intent_sources (intent_id, project_id, fact_id) VALUES ('i001', ?, ?)", projectId, sourceFactId);
        jdbc.update("INSERT OR REPLACE INTO scoped_counters (project_id, kind, value) VALUES (?, 'intent', 1)", projectId);
        jdbc.update("UPDATE projects SET status = 'completed' WHERE id = ?", projectId);
        jdbc.update("""
                UPDATE project_health
                SET completion_status = 'finalized', updated_at = '2026-06-17T00:00:00Z'
                WHERE project_id = ?
                """, projectId);
    }
}
