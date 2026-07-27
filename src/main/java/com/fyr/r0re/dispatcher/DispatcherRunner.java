package com.fyr.r0re.dispatcher;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fyr.r0re.config.R0reProperties;
import com.fyr.r0re.protocol.Models.CompletionCandidate;
import com.fyr.r0re.protocol.Models.CreateRuntimeEventRequest;
import com.fyr.r0re.protocol.Models.Fact;
import com.fyr.r0re.protocol.Models.Intent;
import com.fyr.r0re.protocol.Models.PhaseRuntimeState;
import com.fyr.r0re.protocol.Models.ProjectDetail;
import com.fyr.r0re.protocol.Models.ProjectHealth;
import com.fyr.r0re.protocol.Models.ProjectRuntime;
import com.fyr.r0re.protocol.Models.RuntimeEvent;
import com.fyr.r0re.protocol.Models.ProjectSummary;
import com.fyr.r0re.protocol.Models.RecoveryRecord;
import com.fyr.r0re.protocol.Models.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class DispatcherRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(DispatcherRunner.class);
    private static final int UNHEALTHY_RETRY_AFTER_SECONDS = 5;
    private static final int REJECTED_RETRY_AFTER_SECONDS = 5;
    private static final int STALLED_RECOVERY_COOLDOWN_SECONDS = 15;
    private static final int STALLED_RECOVERY_NATIVE_COOLDOWN_SECONDS = 30;
    private static final int STALLED_RECOVERY_VERIFY_COOLDOWN_SECONDS = 20;
    private static final int STALLED_LIGHT_SECONDS = 15 * 60;
    private static final int STALLED_MEDIUM_SECONDS = 25 * 60;
    private static final int STALLED_HEAVY_SECONDS = 45 * 60;
    private static final int REASON_FAILURE_CHECKPOINT_THRESHOLD = 2;
    private static final int INTENT_FAILURE_BLOCK_THRESHOLD = 3;
    private static final Duration STARTUP_ACTIVATION_GRACE = Duration.ofMinutes(5);
    private static final String BOOTSTRAP_INTENT_DESCRIPTION = "bootstrap";
    private static final String BOOTSTRAP_INTENT_CREATOR = "dispatcher.bootstrap";
    private static final Pattern NATIVE_PHASE_PATTERN = Pattern.compile("\\b(jni|native|\\.so|zygisk|frida|offset|symbol|elf|arm64|lib[a-z0-9_]+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MANIFEST_PHASE_PATTERN = Pattern.compile("\\b(manifest|permission|exported|activity|service|receiver|provider|deep\\s*link)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOURCE_PHASE_PATTERN = Pattern.compile("\\b(resource|string|asset|xml|res/|layout|config)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern JNI_PHASE_PATTERN = Pattern.compile("\\b(jni|loadlibrary|registerNatives|native\\s+method|Java_[A-Za-z0-9_]+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIGNATURE_PHASE_PATTERN = Pattern.compile("\\b(signature|sign|digest|hash|sha1|sha256|md5|certificate)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONSTANT_PHASE_PATTERN = Pattern.compile("\\b(constant|expected|buffer|table|memcmp|strcmp|key\\s*schedule|lookup)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERIFY_PHASE_PATTERN = Pattern.compile("\\b(verify|validation|candidate|input|flag|test\\s*vector|check\\s*result)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPORT_PHASE_PATTERN = Pattern.compile("\\b(report|summary|writeup|document)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPARE_LOOP_PATTERN = Pattern.compile("\\b(compare|memcmp|strcmp|cmp\\b|loop|xor|transform)\\b", Pattern.CASE_INSENSITIVE);

    private final R0reProperties properties;
    private final ConfigurableApplicationContext context;

    private ScheduledExecutorService schedulerExecutor;
    private ExecutorService taskExecutor;
    private ExecutorService cleanupExecutor;
    private DockerCli sharedDocker;
    private final Map<Future<String>, RunningTask> futures = new LinkedHashMap<>();
    private final Map<Future<Boolean>, CleanupTask> cleanupFutures = new LinkedHashMap<>();
    private final Map<String, ReasonCheckpoint> reasonCheckpoints = new HashMap<>();
    private final Map<String, Integer> reasonFailureCounts = new HashMap<>();
    private final Map<String, Integer> intentFailureCounts = new HashMap<>();
    private final Set<String> blockedIntentFailures = new HashSet<>();
    private final Set<String> runtimeProjectIds = new HashSet<>();
    private final Map<String, Long> workerUnhealthyUntil = new HashMap<>();
    private final Map<String, Long> workerRejectedUntil = new HashMap<>();
    private final Set<String> cleanupPending = new HashSet<>();
    private final Map<String, String> inactiveCleanupDone = new HashMap<>();
    private final Set<String> stalledTaskReports = new HashSet<>();
    private final Set<String> stalledTaskRecoveries = new HashSet<>();
    private final Set<String> swarmRaceStarted = new HashSet<>();
    private final Map<String, Instant> swarmAutoResumeAt = new HashMap<>();
    private final Instant dispatcherStartedAt = Instant.now();
    private final Set<String> sessionSchedulableProjectIds = new HashSet<>();
    private final Set<String> startupSkippedProjectIds = new HashSet<>();
    private int projectCursor;
    private boolean settingsChecked;

    public DispatcherRunner(R0reProperties properties, ConfigurableApplicationContext context) {
        this.properties = properties;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.getDispatcher().isEnabled()) {
            LOG.info("dispatcher disabled");
            return;
        }
        ensureStandaloneInvocation();
        DispatchConfig config = loadConfig(properties.getDispatcher().getConfig());
        DispatcherClient client = new DispatcherClient(config.server() == null ? "http://127.0.0.1:8000" : config.server());
        sharedDocker = new DockerCli(config.container());

        taskExecutor = Executors.newFixedThreadPool(Math.max(1, config.runtimeOrDefault().max_workers()), runnable -> {
            Thread thread = new Thread(runnable, "r0re-worker");
            thread.setDaemon(false);
            return thread;
        });
        cleanupExecutor = Executors.newFixedThreadPool(Math.max(1, Math.min(4, config.runtimeOrDefault().max_workers())), runnable -> {
            Thread thread = new Thread(runnable, "r0re-cleanup");
            thread.setDaemon(false);
            return thread;
        });

        try {
            if (properties.getDispatcher().isStartupHealthcheckOnly()) {
                runStartupHealthchecksOnly(config);
                return;
            }
            if (startupHealthcheckEnabled(config)) {
                runStartupHealthchecksOnly(config);
            }
            if (properties.getDispatcher().isOnce()) {
                runOnceUntilIdle(config, client);
                return;
            }
            int interval = Math.max(1, config.runtimeOrDefault().interval());
            schedulerExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "r0re-dispatcher");
                thread.setDaemon(false);
                return thread;
            });
            schedulerExecutor.scheduleWithFixedDelay(() -> tick(config, client), 0, interval, TimeUnit.SECONDS);
            LOG.info("dispatcher started server={} interval={}s config={}", config.server(), interval, properties.getDispatcher().getConfig());
        } finally {
            if (properties.getDispatcher().isOnce() || properties.getDispatcher().isStartupHealthcheckOnly()) {
                closeAll(client);
                context.close();
            }
        }
    }

    private void ensureStandaloneInvocation() {
        if (!properties.getDispatcher().isStandaloneOnly()) {
            return;
        }
        if (properties.getDispatcher().isStandaloneInvocation()) {
            return;
        }
        throw new IllegalStateException("dispatcher must run in standalone mode; use `dispatch` subcommand or set r0re.dispatcher.standalone-only=false");
    }

    private void tick(DispatchConfig config, DispatcherClient client) {
        try {
            validateServerSettings(config, client);
            reapFutures(client);
            reapCleanupFutures();
            List<ProjectSummary> summaries = client.projects();
            initializeReasonCheckpoints(summaries);
            refreshRuntimeProjects(summaries);
            cancelInactiveTasks(summaries);
            // Cairn parity: do not synthesize recovery hints or alter scheduling from artifact/stall heuristics.
            queueContainerCleanups(config, summaries);
            dispatchAvailable(config, client, summaries);
        } catch (Exception exc) {
            LOG.warn("dispatcher loop failed: {}", exc.toString());
        }
    }

    private void runOnceUntilIdle(DispatchConfig config, DispatcherClient client) {
        int maxRounds = Math.max(8, config.runtimeOrDefault().max_workers() * 6);
        for (int round = 0; round < maxRounds; round++) {
            tick(config, client);
            waitForCurrentWork(client);
        }
    }

    private void waitForCurrentWork(DispatcherClient client) {
        for (int i = 0; i < 100; i++) {
            reapFutures(client);
            reapCleanupFutures();
            if (futures.isEmpty() && cleanupFutures.isEmpty()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void dispatchAvailable(DispatchConfig config, DispatcherClient client, List<ProjectSummary> summaries) {
        if ("swarm".equalsIgnoreCase(config.runtimeOrDefault().modeOrDefault())) {
            // swarm: 只有被系统强制停摆的项目(租约过期 → health=recovery_required)才自动恢复;
            // 用户手动 stop 的项目(health 保持 normal)绝不自动复活。
            for (ProjectSummary summary : summaries) {
                if (!"stopped".equals(summary.status())) {
                    continue;
                }
                ProjectHealth health = projectHealthOrNull(client, summary.id());
                if (health != null && "recovery_required".equals(health.health_status())) {
                    tryAutoResumeSwarm(client, summary.id());
                }
            }
        }
        int maxWorkers = Math.max(1, config.runtimeOrDefault().max_workers());
        if (futures.size() >= maxWorkers) {
            return;
        }
        List<ProjectSummary> active = summaries.stream()
                .filter(summary -> "active".equals(summary.status()))
                .filter(summary -> isSessionSchedulableProject(client, summary))
                .toList();
        if (active.isEmpty()) {
            return;
        }

        List<ProjectSummary> runningProjects = orderedProjects(active.stream().filter(summary -> runtimeProjectIds.contains(summary.id())).toList());
        List<ProjectSummary> idleProjects = orderedProjects(active.stream().filter(summary -> !runtimeProjectIds.contains(summary.id())).toList());

        boolean dispatched = true;
        while (dispatched && futures.size() < maxWorkers) {
            dispatched = false;
            for (ProjectSummary summary : runningProjects) {
                if (tryDispatchProject(config, client, summary)) {
                    dispatched = true;
                    if (futures.size() >= maxWorkers) {
                        return;
                    }
                }
            }
            if (dispatched) {
                continue;
            }
            if (runningProjectCount(active) >= Math.max(1, config.runtimeOrDefault().max_running_projects())) {
                return;
            }
            for (ProjectSummary summary : idleProjects) {
                if (runningProjectCount(active) >= Math.max(1, config.runtimeOrDefault().max_running_projects())) {
                    return;
                }
                if (tryDispatchProject(config, client, summary)) {
                    dispatched = true;
                    break;
                }
            }
        }
    }

    private boolean tryDispatchProject(DispatchConfig config, DispatcherClient client, ProjectSummary summary) {
        String containerName = docker(config).containerName(summary.id());
        if (cleanupPending.contains(containerName)) {
            return false;
        }
        if (projectRunningTaskCount(summary.id()) >= Math.max(1, config.runtimeOrDefault().max_project_workers())) {
            return false;
        }

        ProjectDetail project = client.project(summary.id());
        boolean swarm = "swarm".equalsIgnoreCase(config.runtimeOrDefault().modeOrDefault());
        if (!"active".equals(project.project().status())) {
            if (swarm) {
                tryAutoResumeSwarm(client, summary.id());
            }
            return false;
        }
        ProjectHealth health = projectHealthOrNull(client, summary.id());
        if (projectNeedsRepair(health)) {
            // swarm: a crashed worker's lease expiry must not halt the whole project —
            // auto-resume (cooldown-guarded) and let the intent return to open for re-claim.
            if (swarm && health != null && "recovery_required".equals(health.health_status())) {
                tryAutoResumeSwarm(client, summary.id());
            }
            return false;
        }
        if (verifyPendingCompletionCandidate(config, client, project)) {
            return false;
        }
        if (finalizeApprovedCompletionCandidate(config, client, project)) {
            return false;
        }
        if (completionCandidateOpen(client, summary.id())) {
            return false;
        }

        if ("swarm".equalsIgnoreCase(config.runtimeOrDefault().modeOrDefault())) {
            return tryDispatchSwarm(config, client, project, summary);
        }

        if (isInitialProject(project)) {
            if (project.project().reason() != null) {
                return false;
            }
            if (projectRequiresBootstrap(project, config)) {
                return dispatchBootstrap(config, client, project);
            }
            return dispatchReason(config, client, project, "initial");
        }

        if (project.project().reason() == null) {
            String trigger = reasonTrigger(project);
            if (trigger != null) {
                return dispatchReason(config, client, project, trigger);
            }
        }

        List<Intent> unclaimedIntents = unclaimedExploreIntents(project, summary.id());
        if (!unclaimedIntents.isEmpty()) {
            return dispatchExplore(config, client, project, unclaimedIntents.get(0));
        }
        return false;
    }

    private boolean tryDispatchSwarm(DispatchConfig config, DispatcherClient client, ProjectDetail project, ProjectSummary summary) {
        if (isInitialProject(project)) {
            if (project.project().reason() != null) {
                return false;
            }
            if (projectRunningRaceCount(project.project().id()) > 0) {
                return false;
            }
            if (swarmRaceStarted.remove(project.project().id())) {
                finishSwarmRace(client, project.project().id());
                return false;
            }
            return dispatchRace(config, client, project);
        }

        if (project.project().reason() == null) {
            String trigger = reasonTrigger(project);
            if (trigger != null) {
                return dispatchReason(config, client, project, trigger);
            }
        }

        List<Intent> unclaimedIntents = unclaimedExploreIntents(project, summary.id());
        if (!unclaimedIntents.isEmpty()) {
            return dispatchSwarmExplore(config, client, project, unclaimedIntents.get(0));
        }
        return false;
    }

    private boolean isSessionSchedulableProject(DispatcherClient client, ProjectSummary summary) {
        if (summary.id() == null || summary.id().isBlank()) {
            return false;
        }
        if (runtimeProjectIds.contains(summary.id()) || sessionSchedulableProjectIds.contains(summary.id())) {
            return true;
        }
        Instant activationCutoff = dispatcherStartedAt.minus(STARTUP_ACTIVATION_GRACE);
        if (isAtOrAfter(summary.created_at(), activationCutoff) || hasManualActivationEventSince(client, summary.id(), activationCutoff)) {
            sessionSchedulableProjectIds.add(summary.id());
            startupSkippedProjectIds.remove(summary.id());
            return true;
        }
        if (startupSkippedProjectIds.add(summary.id())) {
            LOG.info("skipping historical active project={} created_at={} dispatcher_started_at={}; resume it from WebUI to schedule",
                    summary.id(), summary.created_at(), dispatcherStartedAt);
        }
        return false;
    }

    private boolean hasManualActivationEventSince(DispatcherClient client, String projectId, Instant cutoff) {
        ProjectRuntime runtime;
        try {
            runtime = client.projectRuntime(projectId);
        } catch (Exception exc) {
            LOG.debug("activation event lookup failed project={} error={}", projectId, exc.toString());
            return false;
        }
        if (runtime == null || runtime.recent_events() == null) {
            return false;
        }
        for (RuntimeEvent event : runtime.recent_events()) {
            if (event == null || event.event_type() == null) {
                continue;
            }
            boolean activationEvent = "project_execution_resumed".equals(event.event_type())
                    || "dispatcher_wakeup_requested".equals(event.event_type());
            if (!activationEvent) {
                continue;
            }
            if ("r0re.dispatcher".equals(event.worker())) {
                continue;
            }
            if (isAtOrAfter(event.created_at(), cutoff)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAtOrAfter(String timestamp, Instant cutoff) {
        if (timestamp == null || timestamp.isBlank()) {
            return false;
        }
        try {
            Instant value = Instant.parse(timestamp);
            return !value.isBefore(cutoff);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void tryAutoResumeSwarm(DispatcherClient client, String projectId) {
        Instant now = Instant.now();
        Instant last = swarmAutoResumeAt.get(projectId);
        if (last != null && last.plus(Duration.ofMinutes(5)).isAfter(now)) {
            return;
        }
        swarmAutoResumeAt.put(projectId, now);
        try {
            client.resumeExecution(projectId, "r0re.dispatcher");
            LOG.info("swarm auto-resumed project={} (recovery_required/stopped)", projectId);
        } catch (Exception exc) {
            LOG.warn("swarm auto-resume failed project={} error={}", projectId, exc.toString());
        }
    }

    private List<Intent> unclaimedExploreIntents(ProjectDetail project, String projectId) {
        Set<String> runningExploreIntents = projectRunningExploreIntents(projectId);
        return project.intents().stream()
                .filter(intent -> intent.to() == null)
                .filter(intent -> intent.worker() == null)
                .filter(intent -> !runningExploreIntents.contains(intent.id()))
                .filter(intent -> !blockedIntentFailures.contains(intentFailureKey(projectId, intent.id())))
                .filter(intent -> !isBootstrapIntent(intent))
                .sorted(Comparator.comparing(Intent::created_at, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private boolean finalizeApprovedCompletionCandidate(DispatchConfig config, DispatcherClient client, ProjectDetail project) {
        if (!config.runtimeOrDefault().completionAutoFinalize()) {
            return false;
        }
        List<CompletionCandidate> candidates;
        try {
            candidates = client.completionCandidates(project.project().id());
        } catch (Exception exc) {
            LOG.debug("completion candidate fetch failed project={} error={}", project.project().id(), exc.toString());
            return false;
        }
        CompletionCandidate candidate = candidates.stream()
                .filter(item -> "approved".equals(item.verification_status()))
                .findFirst()
                .orElse(null);
        if (candidate == null) {
            return false;
        }

        String finalizer = "r0re.finalizer";
        try {
            client.finalizeCompletion(project.project().id(), candidate.id(), finalizer);
            recordTaskEvent(client, project.project().id(), "project", project.project().id(), finalizer,
                    "completion_candidate_auto_finalized", "finalize", "complete",
                    "completion candidate " + candidate.id() + " finalized");
            persistProjectExports(config, client, project.project().id());
            snapshotProjectWorkspace(config, project.project().id());
            return true;
        } catch (Exception exc) {
            LOG.warn("completion candidate finalize failed project={} candidate={} error={}",
                    project.project().id(), candidate.id(), exc.toString());
            return false;
        }
    }

    private boolean verifyPendingCompletionCandidate(DispatchConfig config, DispatcherClient client, ProjectDetail project) {
        List<CompletionCandidate> candidates;
        try {
            candidates = client.completionCandidates(project.project().id());
        } catch (Exception exc) {
            LOG.debug("completion candidate fetch failed project={} error={}", project.project().id(), exc.toString());
            return false;
        }
        CompletionCandidate candidate = candidates.stream()
                .filter(item -> "pending".equals(item.verification_status()))
                .findFirst()
                .orElse(null);
        if (candidate == null) {
            return false;
        }

        VerifierIdentity verifierIdentity = verifierIdentity(project, config);
        String verifier = verifierIdentity.worker();
        try {
            client.startCompletionVerification(project.project().id(), candidate.id(), verifier, verifierIdentity.promptGroup(), verifierIdentity.tool());
            String rejectionReason = completionCandidateRejectionReason(project, candidate);
            if (rejectionReason == null) {
                client.approveCompletionCandidate(project.project().id(), candidate.id());
                client.appendRuntimeEvent(project.project().id(), new CreateRuntimeEventRequest(
                        "project",
                        project.project().id(),
                        verifier,
                        "completion_candidate_auto_approved",
                        "verify",
                        verifierIdentity.promptGroup(),
                        "completion candidate " + candidate.id() + " passed rule verifier",
                        candidate.artifact_path(),
                        verifierIdentity.tool(),
                        null,
                        null
                ));
            } else {
                client.rejectCompletionCandidate(project.project().id(), candidate.id());
                client.quarantineProject(project.project().id(), "completion candidate " + candidate.id() + " rejected: " + rejectionReason, verifier);
                client.appendRuntimeEvent(project.project().id(), new CreateRuntimeEventRequest(
                        "project",
                        project.project().id(),
                        verifier,
                        "completion_candidate_auto_rejected",
                        "verify",
                        verifierIdentity.promptGroup(),
                        "completion candidate " + candidate.id() + " rejected: " + rejectionReason,
                        candidate.artifact_path(),
                        verifierIdentity.tool(),
                        null,
                        null
                ));
            }
            return true;
        } catch (Exception exc) {
            LOG.warn("completion candidate verification failed project={} candidate={} error={}",
                    project.project().id(), candidate.id(), exc.toString());
            try {
                client.failCompletionVerification(project.project().id(), candidate.id(), verifier, exc.toString(), true);
            } catch (Exception failureExc) {
                LOG.warn("completion candidate failure state update failed project={} candidate={} error={}",
                        project.project().id(), candidate.id(), failureExc.toString());
            }
            return false;
        }
    }

    private String completionCandidateRejectionReason(ProjectDetail project, CompletionCandidate candidate) {
        if (!"goal".equals(candidate.candidate_type())) {
            return "candidate_type must be goal";
        }
        if (candidate.candidate_value() == null || candidate.candidate_value().isBlank()) {
            return "candidate_value is blank";
        }
        if (candidate.worker() == null || candidate.worker().isBlank()) {
            return "worker is blank";
        }
        if (candidate.worker().toLowerCase(java.util.Locale.ROOT).contains("mock")) {
            return "mock worker cannot complete real projects";
        }
        Set<String> facts = project.facts().stream().map(Fact::id).collect(java.util.stream.Collectors.toSet());
        List<String> sources = candidateSourceFacts(candidate);
        if (sources.isEmpty()) {
            return "source facts are missing";
        }
        if (sources.contains("goal")) {
            return "goal cannot be used as source fact";
        }
        for (String source : sources) {
            if (!facts.contains(source)) {
                return "source fact not found: " + source;
            }
        }
        return null;
    }

    private List<String> candidateSourceFacts(CompletionCandidate candidate) {
        if (candidate.evidence_summary() == null || candidate.evidence_summary().isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(candidate.evidence_summary().split(","))
                .map(String::trim)
                .filter(source -> !source.isEmpty())
                .distinct()
                .toList();
    }

    private boolean completionCandidateOpen(DispatcherClient client, String projectId) {
        try {
            ProjectHealth health = client.projectHealth(projectId);
            if (health == null || health.completion_status() == null) {
                return false;
            }
            return Set.of("candidate_pending", "verifying", "approved", "retryable_failed", "manual_review")
                    .contains(health.completion_status());
        } catch (Exception exc) {
            LOG.debug("project health check failed project={} error={}", projectId, exc.toString());
            return false;
        }
    }

    private VerifierIdentity verifierIdentity(ProjectDetail project, DispatchConfig config) {
        DispatchConfig.VerifierConfig verifier = config.verifierOrDefault();
        String mode = verifierMode(project);
        if ("android_ctf".equals(mode)) {
            return new VerifierIdentity(
                    firstNonBlank(verifier.android_ctf_worker(), verifier.reverse_worker(), verifier.default_worker(), "r0re.rule-verifier"),
                    firstNonBlank(verifier.android_ctf_prompt_group(), verifier.reverse_prompt_group(), verifier.prompt_group(), "android-ctf-verifier"),
                    firstNonBlank(verifier.tool(), "rule-verifier")
            );
        }
        if ("reverse".equals(mode)) {
            return new VerifierIdentity(
                    firstNonBlank(verifier.reverse_worker(), verifier.default_worker(), "r0re.rule-verifier"),
                    firstNonBlank(verifier.reverse_prompt_group(), verifier.prompt_group(), "reverse-verifier"),
                    firstNonBlank(verifier.tool(), "rule-verifier")
            );
        }
        return new VerifierIdentity(
                firstNonBlank(verifier.default_worker(), "r0re.rule-verifier"),
                firstNonBlank(verifier.prompt_group(), "verify"),
                firstNonBlank(verifier.tool(), "rule-verifier")
        );
    }

    private String verifierMode(ProjectDetail project) {
        StringBuilder text = new StringBuilder();
        if (project != null && project.project() != null && project.project().title() != null) {
            text.append(project.project().title()).append(' ');
        }
        if (project != null && project.facts() != null) {
            for (Fact fact : project.facts()) {
                if ("origin".equals(fact.id()) || "goal".equals(fact.id())) {
                    text.append(fact.description()).append(' ');
                }
            }
        }
        String value = text.toString().toLowerCase(java.util.Locale.ROOT);
        if (value.contains("android") || value.contains("apk") || value.contains("ctf") || value.contains("crackme")) {
            return "android_ctf";
        }
        if (value.contains("reverse") || value.contains("jni") || value.contains("native")
                || value.contains("smali") || value.contains("jadx") || value.contains(".so")) {
            return "reverse";
        }
        return "default";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private ProjectHealth projectHealthOrNull(DispatcherClient client, String projectId) {
        try {
            return client.projectHealth(projectId);
        } catch (Exception exc) {
            LOG.debug("project health check failed project={} error={}", projectId, exc.toString());
            return null;
        }
    }

    private boolean projectNeedsRepair(ProjectHealth health) {
        if (health == null || health.health_status() == null) {
            return false;
        }
        return Set.of("needs_review", "quarantined", "recovery_required").contains(health.health_status());
    }

    private boolean dispatchBootstrap(DispatchConfig config, DispatcherClient client, ProjectDetail project) {
        Intent intent = getBootstrapIntent(project);
        if (intent == null) {
            intent = createBootstrapIntent(client, project.project().id());
            if (intent == null) {
                return false;
            }
        }
        if (projectHasRunningBootstrap(project.project().id())) {
            return false;
        }
        if (intent.worker() != null) {
            return false;
        }

        WorkerSelection selection = selectWorker(config, project.project().id(), "bootstrap");
        if (selection.worker() == null) {
            return false;
        }
        DispatchConfig.WorkerConfig worker = selection.worker();
        String phase = "bootstrap";
        String costClass = costClassForPhase(phase);
        if (!canScheduleCostClass(project.project().id(), costClass)) {
            return false;
        }
        DispatcherClient.ApiResult claim = client.heartbeatResult(project.project().id(), intent.id(), worker.name());
        if (!claim.ok()) {
            return false;
        }

        TaskCancellation cancellation = new TaskCancellation();
        String claimedIntentId = intent.id();
        Intent claimedIntent = client.project(project.project().id()).intents().stream()
                .filter(item -> item.id().equals(claimedIntentId))
                .findFirst()
                .orElse(intent);
        Future<String> future = taskExecutor.submit(() -> runBootstrapTask(config, client, project, claimedIntent, worker, cancellation));
        recordTaskEvent(client, project.project().id(), "intent", claimedIntent.id(), worker.name(), "task_dispatched", phase, "claim", "bootstrap dispatched [cost=" + costClass + "]");
        String now = Instant.now().toString();
        futures.put(future, new RunningTask(project.project().id(), "bootstrap", worker.name(), cancellation, claimedIntent.id(), phase, costClass, "running", now, now, 0, null, preferredArtifactTypeForPhase(phase), 0, null, null, null, null, null));
        runtimeProjectIds.add(project.project().id());
        LOG.info("dispatched bootstrap project={} intent={} worker={}", project.project().id(), claimedIntent.id(), worker.name());
        return true;
    }

    private boolean dispatchExplore(DispatchConfig config, DispatcherClient client, ProjectDetail project, Intent intent) {
        String phase = classifyIntentPhase(intent);
        String costClass = costClassForPhase(phase);
        if (!canScheduleCostClass(project.project().id(), costClass)) {
            return false;
        }
        WorkerSelection selection = selectWorker(config, project.project().id(), "explore");
        if (selection.worker() == null) {
            return false;
        }
        DispatchConfig.WorkerConfig worker = selection.worker();
        DispatcherClient.ApiResult claim = client.heartbeatResult(project.project().id(), intent.id(), worker.name());
        if (!claim.ok()) {
            return false;
        }

        TaskCancellation cancellation = new TaskCancellation();
        String claimedIntentId = intent.id();
        Intent claimedIntent = client.project(project.project().id()).intents().stream()
                .filter(item -> item.id().equals(claimedIntentId))
                .findFirst()
                .orElse(intent);
        Future<String> future = taskExecutor.submit(() -> runExploreTask(config, client, project, claimedIntent, worker, cancellation));
        recordTaskEvent(client, project.project().id(), "intent", claimedIntent.id(), worker.name(), "task_dispatched", phase, "claim", "explore dispatched [cost=" + costClass + "]");
        String now = Instant.now().toString();
        futures.put(future, new RunningTask(project.project().id(), "explore", worker.name(), cancellation, claimedIntent.id(), phase, costClass, "running", now, now, 0, null, preferredArtifactTypeForPhase(phase), 0, null, null, null, null, null));
        runtimeProjectIds.add(project.project().id());
        LOG.info("dispatched explore project={} intent={} worker={} phase={} cost={}", project.project().id(), claimedIntent.id(), worker.name(), phase, costClass);
        return true;
    }

    private boolean dispatchRace(DispatchConfig config, DispatcherClient client, ProjectDetail project) {
        String phase = "race";
        String costClass = costClassForPhase(phase);
        int maxWorkers = Math.max(1, config.runtimeOrDefault().max_workers());
        int maxProjectWorkers = Math.max(1, config.runtimeOrDefault().max_project_workers());
        int slots = Math.min(3, Math.min(maxWorkers - futures.size(), maxProjectWorkers - projectRunningTaskCount(project.project().id())));
        if (slots <= 0) {
            return false;
        }
        int dispatched = 0;
        for (int i = 0; i < slots; i++) {
            if (!canScheduleCostClass(project.project().id(), costClass)) {
                break;
            }
            WorkerSelection selection = selectWorker(config, project.project().id(), "bootstrap");
            if (selection.worker() == null) {
                break;
            }
            DispatchConfig.WorkerConfig worker = selection.worker();
            String swarmWorkerName = uniqueSwarmWorkerName(worker);
            TaskCancellation cancellation = new TaskCancellation();
            Future<String> future = taskExecutor.submit(() -> runRaceTask(config, client, project, worker, swarmWorkerName, cancellation));
            recordTaskEvent(client, project.project().id(), "project", project.project().id(), swarmWorkerName, "task_dispatched", phase, "race", "race dispatched [cost=" + costClass + "]");
            String now = Instant.now().toString();
            futures.put(future, new RunningTask(project.project().id(), "race", worker.name(), cancellation, null, phase, costClass, "running", now, now, 0, null, preferredArtifactTypeForPhase(phase), 0, null, null, null, null, null));
            runtimeProjectIds.add(project.project().id());
            dispatched++;
            LOG.info("dispatched race project={} worker={} swarm_worker={}", project.project().id(), worker.name(), swarmWorkerName);
        }
        if (dispatched > 0) {
            swarmRaceStarted.add(project.project().id());
        }
        return dispatched > 0;
    }

    private void finishSwarmRace(DispatcherClient client, String projectId) {
        try {
            client.blackboardAddFact(projectId, "r0re.dispatcher",
                    "race phase finished: no conclusive result, entering coordinator loop", false);
            LOG.info("race phase finished without facts project={} — entering coordinator loop", projectId);
        } catch (Exception exc) {
            LOG.warn("race finish fact write failed project={} error={}", projectId, exc.toString());
            swarmRaceStarted.add(projectId);
        }
    }

    private boolean dispatchSwarmExplore(DispatchConfig config, DispatcherClient client, ProjectDetail project, Intent intent) {
        String phase = classifyIntentPhase(intent);
        String costClass = costClassForPhase(phase);
        if (!canScheduleCostClass(project.project().id(), costClass)) {
            return false;
        }
        WorkerSelection selection = selectWorker(config, project.project().id(), "explore");
        if (selection.worker() == null) {
            return false;
        }
        DispatchConfig.WorkerConfig worker = selection.worker();
        String swarmWorkerName = uniqueSwarmWorkerName(worker);
        TaskCancellation cancellation = new TaskCancellation();
        Future<String> future = taskExecutor.submit(() -> runSwarmExploreTask(config, client, project, worker, swarmWorkerName, cancellation));
        recordTaskEvent(client, project.project().id(), "intent", intent.id(), swarmWorkerName, "task_dispatched", phase, "swarm", "swarm explore dispatched without pre-claim [cost=" + costClass + "]");
        String now = Instant.now().toString();
        futures.put(future, new RunningTask(project.project().id(), "explore", worker.name(), cancellation, intent.id(), phase, costClass, "running", now, now, 0, null, preferredArtifactTypeForPhase(phase), 0, null, null, null, null, null));
        runtimeProjectIds.add(project.project().id());
        LOG.info("dispatched swarm explore project={} intent={} worker={} swarm_worker={} phase={} cost={}",
                project.project().id(), intent.id(), worker.name(), swarmWorkerName, phase, costClass);
        return true;
    }

    private String uniqueSwarmWorkerName(DispatchConfig.WorkerConfig worker) {
        return worker.name() + "-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private int projectRunningRaceCount(String projectId) {
        int count = 0;
        for (RunningTask task : futures.values()) {
            if (projectId.equals(task.projectId()) && "race".equals(task.taskType())) {
                count++;
            }
        }
        return count;
    }

    private boolean dispatchReason(DispatchConfig config, DispatcherClient client, ProjectDetail project, String trigger) {
        String phase = classifyReasonPhase(project);
        String costClass = costClassForPhase(phase);
        if (!canScheduleCostClass(project.project().id(), costClass)) {
            return false;
        }
        WorkerSelection selection = selectWorker(config, project.project().id(), "reason");
        if (selection.worker() == null) {
            return false;
        }
        DispatchConfig.WorkerConfig worker = selection.worker();
        DispatcherClient.ApiResult claim = client.claimReason(project.project().id(), worker.name(), trigger);
        if (!claim.ok()) {
            return false;
        }
        TaskCancellation cancellation = new TaskCancellation();
        Future<String> future = taskExecutor.submit(() -> runReasonTask(config, client, project, worker, cancellation));
        recordTaskEvent(client, project.project().id(), "reason", "reason", worker.name(), "task_dispatched", phase, trigger, "reason dispatched [cost=" + costClass + "]");
        String now = Instant.now().toString();
        futures.put(future, new RunningTask(
                project.project().id(),
                "reason",
                worker.name(),
                cancellation,
                null,
                phase,
                costClass,
                "running",
                now,
                now,
                0,
                null,
                preferredArtifactTypeForPhase(phase),
                0,
                null,
                project.facts().size(),
                project.hints().size(),
                projectOpenIntentCount(project),
                trigger
        ));
        runtimeProjectIds.add(project.project().id());
        LOG.info("dispatched reason project={} worker={} trigger={} phase={} cost={}", project.project().id(), worker.name(), trigger, phase, costClass);
        return true;
    }

    private String runBootstrapTask(DispatchConfig config, DispatcherClient client, ProjectDetail project, Intent intent, DispatchConfig.WorkerConfig worker, TaskCancellation cancellation) {
        HeartbeatLease lease = HeartbeatLease.forIntent(client, project.project().id(), intent.id(), worker.name(), config.runtimeOrDefault().interval(), cancellation);
        recordTaskEvent(client, project.project().id(), "intent", intent.id(), worker.name(), "task_started", "bootstrap", "execute", "bootstrap started");
        lease.update("bootstrap", "healthcheck", "worker healthcheck pending", null, worker.type(), 0, 3);
        lease.start();
        try {
            WorkerAdapter adapter = WorkerAdapters.forType(worker.type(), config.container(), config.runtimeOrDefault().prompt_group(), config.server(), docker(config));
            if (taskHealthcheckEnabled(config)) {
                adapter.taskHealthcheck(project.project().id(), worker, config.runtimeOrDefault().healthcheck_timeout());
            }
            lease.update("bootstrap", "execute", "worker bootstrap execution running", null, worker.type(), 1, 3);
            if (cancellation.isCancelled()) {
                bestEffortRelease(client, project.project().id(), intent.id(), worker.name());
                return "cancelled";
            }
            WorkerResult result = adapter.bootstrap(project, worker, config.tasks() == null ? null : config.tasks().bootstrap(), cancellation);
            if (lease.failure() != null || cancellation.isCancelled()) {
                recordTaskEvent(client, project.project().id(), "intent", intent.id(), worker.name(), "task_cancelled", "bootstrap", "cancel", "bootstrap cancelled");
                bestEffortRelease(client, project.project().id(), intent.id(), worker.name());
                return "cancelled";
            }
            lease.update("bootstrap", "apply_result", "applying bootstrap result", null, worker.type(), 2, 3);
            String outcome = applyBootstrapResult(config, project, intent, worker, result, client);
            lease.update("bootstrap", "completed", "bootstrap result applied", null, worker.type(), 3, 3);
            recordTaskEvent(client, project.project().id(), "intent", intent.id(), worker.name(), "task_finished", "bootstrap", outcome, "bootstrap finished");
            return outcome;
            } catch (java.util.concurrent.CancellationException exc) {
                recordTaskEvent(client, project.project().id(), "intent", intent.id(), worker.name(), "task_cancelled", "bootstrap", "cancel", "bootstrap cancelled");
                bestEffortRelease(client, project.project().id(), intent.id(), worker.name());
                return "cancelled";
            } catch (ExternalCliWorkerAdapter.QuotaExceededException exc) {
                recordTaskEvent(client, project.project().id(), "intent", intent.id(), worker.name(), "task_quota_exceeded", "bootstrap", "quota", exc.getMessage());
                bestEffortRelease(client, project.project().id(), intent.id(), worker.name());
                bestEffortStopProject(client, project.project().id(), exc.getMessage());
                return "quota_exceeded";
            } catch (ExternalCliWorkerAdapter.RetryableWorkerException exc) {
                recordTaskEvent(client, project.project().id(), "intent", intent.id(), worker.name(), "task_retryable_error", "bootstrap", "retry", exc.getMessage());
                bestEffortRelease(client, project.project().id(), intent.id(), worker.name());
                return "unhealthy";
            } catch (Exception exc) {
                recordTaskEvent(client, project.project().id(), "intent", intent.id(), worker.name(), "task_failed", "bootstrap", "error", exc.toString());
                bestEffortRelease(client, project.project().id(), intent.id(), worker.name());
                return "failed";
            } finally {
                lease.stop();
        }
    }

    private String runExploreTask(DispatchConfig config, DispatcherClient client, ProjectDetail project, Intent intent, DispatchConfig.WorkerConfig worker, TaskCancellation cancellation) {
        HeartbeatLease lease = HeartbeatLease.forIntent(client, project.project().id(), intent.id(), worker.name(), config.runtimeOrDefault().interval(), cancellation);
        recordTaskEvent(client, project.project().id(), "intent", intent.id(), worker.name(), "task_started", "explore", "execute", "explore started");
        lease.update("explore", "healthcheck", "worker healthcheck pending", null, worker.type(), 0, 3);
        lease.start();
        try {
            WorkerAdapter adapter = WorkerAdapters.forType(worker.type(), config.container(), config.runtimeOrDefault().prompt_group(), config.server(), docker(config));
            if (taskHealthcheckEnabled(config)) {
                adapter.taskHealthcheck(project.project().id(), worker, config.runtimeOrDefault().healthcheck_timeout());
            }
            lease.update("explore", "execute", "worker explore execution running", null, worker.type(), 1, 3);
            if (cancellation.isCancelled()) {
                bestEffortRelease(client, project.project().id(), intent.id(), worker.name());
                return "cancelled";
            }
            WorkerResult result = adapter.explore(project, intent, worker, config.tasks() == null ? null : config.tasks().explore(), cancellation);
            if (lease.failure() != null || cancellation.isCancelled()) {
                recordTaskEvent(client, project.project().id(), "intent", intent.id(), worker.name(), "task_cancelled", "explore", "cancel", "explore cancelled");
                bestEffortRelease(client, project.project().id(), intent.id(), worker.name());
                return "cancelled";
            }
            lease.update("explore", "apply_result", "applying explore result", null, worker.type(), 2, 3);
            String outcome = applyExploreResult(config, project, intent, worker, result, client, phaseForIntent(intent));
            lease.update("explore", "completed", "explore result applied", null, worker.type(), 3, 3);
            recordTaskEvent(client, project.project().id(), "intent", intent.id(), worker.name(), "task_finished", "explore", outcome, "explore finished");
            return outcome;
            } catch (java.util.concurrent.CancellationException exc) {
                recordTaskEvent(client, project.project().id(), "intent", intent.id(), worker.name(), "task_cancelled", "explore", "cancel", "explore cancelled");
                bestEffortRelease(client, project.project().id(), intent.id(), worker.name());
                return "cancelled";
            } catch (ExternalCliWorkerAdapter.QuotaExceededException exc) {
                recordTaskEvent(client, project.project().id(), "intent", intent.id(), worker.name(), "task_quota_exceeded", "explore", "quota", exc.getMessage());
                bestEffortRelease(client, project.project().id(), intent.id(), worker.name());
                bestEffortStopProject(client, project.project().id(), exc.getMessage());
                return "quota_exceeded";
            } catch (ExternalCliWorkerAdapter.RetryableWorkerException exc) {
                recordTaskEvent(client, project.project().id(), "intent", intent.id(), worker.name(), "task_retryable_error", "explore", "retry", exc.getMessage());
                bestEffortRelease(client, project.project().id(), intent.id(), worker.name());
                return "unhealthy";
            } catch (Exception exc) {
                recordTaskEvent(client, project.project().id(), "intent", intent.id(), worker.name(), "task_failed", "explore", "error", exc.toString());
                bestEffortRelease(client, project.project().id(), intent.id(), worker.name());
                return "failed";
            } finally {
                lease.stop();
        }
    }

    private String runRaceTask(DispatchConfig config, DispatcherClient client, ProjectDetail project, DispatchConfig.WorkerConfig worker, String swarmWorkerName, TaskCancellation cancellation) {
        recordTaskEvent(client, project.project().id(), "project", project.project().id(), swarmWorkerName, "task_started", "race", "execute", "race started");
        try {
            WorkerAdapter adapter = WorkerAdapters.forType(worker.type(), config.container(), config.runtimeOrDefault().prompt_group(), config.server(), docker(config));
            if (taskHealthcheckEnabled(config)) {
                adapter.taskHealthcheck(project.project().id(), worker, config.runtimeOrDefault().healthcheck_timeout());
            }
            if (cancellation.isCancelled()) {
                return "cancelled";
            }
            WorkerResult result = adapter.race(project, worker, swarmWorkerName, config.tasks() == null ? null : config.tasks().bootstrap(), cancellation);
            if (cancellation.isCancelled()) {
                recordTaskEvent(client, project.project().id(), "project", project.project().id(), swarmWorkerName, "task_cancelled", "race", "cancel", "race cancelled");
                return "cancelled";
            }
            String outcome = applyRaceResult(config, project, swarmWorkerName, result, client);
            recordTaskEvent(client, project.project().id(), "project", project.project().id(), swarmWorkerName, "task_finished", "race", outcome, "race finished");
            return outcome;
        } catch (java.util.concurrent.CancellationException exc) {
            recordTaskEvent(client, project.project().id(), "project", project.project().id(), swarmWorkerName, "task_cancelled", "race", "cancel", "race cancelled");
            return "cancelled";
        } catch (ExternalCliWorkerAdapter.QuotaExceededException exc) {
            recordTaskEvent(client, project.project().id(), "project", project.project().id(), swarmWorkerName, "task_quota_exceeded", "race", "quota", exc.getMessage());
            bestEffortStopProject(client, project.project().id(), exc.getMessage());
            return "quota_exceeded";
        } catch (ExternalCliWorkerAdapter.RetryableWorkerException exc) {
            recordTaskEvent(client, project.project().id(), "project", project.project().id(), swarmWorkerName, "task_retryable_error", "race", "retry", exc.getMessage());
            return "unhealthy";
        } catch (Exception exc) {
            recordTaskEvent(client, project.project().id(), "project", project.project().id(), swarmWorkerName, "task_failed", "race", "error", exc.toString());
            return "failed";
        }
    }

    private String runSwarmExploreTask(DispatchConfig config, DispatcherClient client, ProjectDetail project, DispatchConfig.WorkerConfig worker, String swarmWorkerName, TaskCancellation cancellation) {
        String phase = "swarm_explore";
        recordTaskEvent(client, project.project().id(), "project", project.project().id(), swarmWorkerName, "task_started", phase, "execute", "swarm explore started");
        SwarmIntentHeartbeat heartbeat = new SwarmIntentHeartbeat(client, project.project().id(), swarmWorkerName, config.runtimeOrDefault().interval(), cancellation);
        heartbeat.start();
        try {
            WorkerAdapter adapter = WorkerAdapters.forType(worker.type(), config.container(), config.runtimeOrDefault().prompt_group(), config.server(), docker(config));
            if (taskHealthcheckEnabled(config)) {
                adapter.taskHealthcheck(project.project().id(), worker, config.runtimeOrDefault().healthcheck_timeout());
            }
            if (cancellation.isCancelled()) {
                return "cancelled";
            }
            WorkerResult result = adapter.swarmExplore(project, worker, swarmWorkerName, config.tasks() == null ? null : config.tasks().explore(), cancellation);
            if (cancellation.isCancelled()) {
                recordTaskEvent(client, project.project().id(), "project", project.project().id(), swarmWorkerName, "task_cancelled", phase, "cancel", "swarm explore cancelled");
                return "cancelled";
            }
            String outcome = applySwarmExploreResult(config, project, swarmWorkerName, result, client);
            recordTaskEvent(client, project.project().id(), "project", project.project().id(), swarmWorkerName, "task_finished", phase, outcome, "swarm explore finished");
            return outcome;
        } catch (java.util.concurrent.CancellationException exc) {
            recordTaskEvent(client, project.project().id(), "project", project.project().id(), swarmWorkerName, "task_cancelled", phase, "cancel", "swarm explore cancelled");
            return "cancelled";
        } catch (ExternalCliWorkerAdapter.QuotaExceededException exc) {
            recordTaskEvent(client, project.project().id(), "project", project.project().id(), swarmWorkerName, "task_quota_exceeded", phase, "quota", exc.getMessage());
            bestEffortStopProject(client, project.project().id(), exc.getMessage());
            return "quota_exceeded";
        } catch (ExternalCliWorkerAdapter.RetryableWorkerException exc) {
            recordTaskEvent(client, project.project().id(), "project", project.project().id(), swarmWorkerName, "task_retryable_error", phase, "retry", exc.getMessage());
            return "unhealthy";
        } catch (Exception exc) {
            recordTaskEvent(client, project.project().id(), "project", project.project().id(), swarmWorkerName, "task_failed", phase, "error", exc.toString());
            return "failed";
        } finally {
            heartbeat.stop();
        }
    }

    private String applyRaceResult(DispatchConfig config, ProjectDetail project, String swarmWorkerName, WorkerResult result, DispatcherClient client) {
        if (result instanceof WorkerResult.Rejected) {
            return "rejected";
        }
        if (result instanceof WorkerResult.Complete complete) {
            List<String> from = currentNonGoalFactIds(client, project);
            client.complete(project.project().id(), from, complete.description(), swarmWorkerName);
            persistProjectExports(config, client, project.project().id());
            snapshotProjectWorkspace(config, project.project().id());
            return "success";
        }
        if (result instanceof WorkerResult.Fact fact) {
            client.blackboardAddFact(project.project().id(), swarmWorkerName, fact.description(), false);
            return "success";
        }
        if (result == null) {
            LOG.info("race worker finished without terminal result project={} worker={} — board-only", project.project().id(), swarmWorkerName);
            return "success";
        }
        return "failed";
    }

    private List<String> currentNonGoalFactIds(DispatcherClient client, ProjectDetail project) {
        try {
            ProjectDetail current = client.project(project.project().id());
            if (current != null && current.facts() != null) {
                return current.facts().stream()
                        .map(Fact::id)
                        .filter(id -> !"goal".equals(id))
                        .toList();
            }
        } catch (Exception exc) {
            LOG.debug("project refetch failed project={} error={}", project.project().id(), exc.toString());
        }
        return project.facts().stream()
                .map(Fact::id)
                .filter(id -> !"goal".equals(id))
                .toList();
    }

    private String applySwarmExploreResult(DispatchConfig config, ProjectDetail project, String swarmWorkerName, WorkerResult result, DispatcherClient client) {        if (result instanceof WorkerResult.Rejected) {
            return "rejected";
        }
        if (result instanceof WorkerResult.Complete complete) {
            client.complete(project.project().id(), complete.from(), complete.description(), swarmWorkerName);
            persistProjectExports(config, client, project.project().id());
            snapshotProjectWorkspace(config, project.project().id());
            return "success";
        }
        if (result instanceof WorkerResult.Fact fact) {
            client.blackboardAddFact(project.project().id(), swarmWorkerName, fact.description(), false);
            return "success";
        }
        if (result == null) {
            LOG.info("swarm explore worker finished without terminal result project={} worker={} — board-only", project.project().id(), swarmWorkerName);
            return "success";
        }
        return "failed";
    }

    private String runReasonTask(DispatchConfig config, DispatcherClient client, ProjectDetail project, DispatchConfig.WorkerConfig worker, TaskCancellation cancellation) {
        HeartbeatLease lease = HeartbeatLease.forReason(client, project.project().id(), worker.name(), config.runtimeOrDefault().interval(), cancellation);
        recordTaskEvent(client, project.project().id(), "reason", "reason", worker.name(), "task_started", "reason", "execute", "reason started");
        lease.update("reason", "healthcheck", "worker healthcheck pending", null, worker.type(), 0, 3);
        lease.start();
        try {
            WorkerAdapter adapter = WorkerAdapters.forType(worker.type(), config.container(), config.runtimeOrDefault().prompt_group(), config.server(), docker(config));
            if (taskHealthcheckEnabled(config)) {
                adapter.taskHealthcheck(project.project().id(), worker, config.runtimeOrDefault().healthcheck_timeout());
            }
            lease.update("reason", "execute", "worker reason execution running", null, worker.type(), 1, 3);
            if (cancellation.isCancelled()) {
                bestEffortReleaseReason(client, project.project().id(), worker.name());
                return "cancelled";
            }
            Settings settings = client.settings();
            DispatchConfig.ReasonTask configuredReason = config.tasks() == null ? null : config.tasks().reason();
            DispatchConfig.ReasonTask effectiveReason = new DispatchConfig.ReasonTask(
                    configuredReason == null ? settings.reason_timeout() : configuredReason.timeout(),
                    configuredReason == null ? 2 : Math.max(1, configuredReason.max_intents()));
            WorkerResult result = adapter.reason(project, worker, effectiveReason, cancellation);
            if (lease.failure() != null || cancellation.isCancelled()) {
                recordTaskEvent(client, project.project().id(), "reason", "reason", worker.name(), "task_cancelled", "reason", "cancel", "reason cancelled");
                bestEffortReleaseReason(client, project.project().id(), worker.name());
                return "cancelled";
            }
            lease.update("reason", "apply_result", "applying reason result", null, worker.type(), 2, 3);
            String outcome = applyReasonResult(config, project, worker, result, client, classifyReasonPhase(project));
            lease.update("reason", "completed", "reason result applied", null, worker.type(), 3, 3);
            recordTaskEvent(client, project.project().id(), "reason", "reason", worker.name(), "task_finished", "reason", outcome, "reason finished");
            return outcome;
            } catch (java.util.concurrent.CancellationException exc) {
                recordTaskEvent(client, project.project().id(), "reason", "reason", worker.name(), "task_cancelled", "reason", "cancel", "reason cancelled");
                bestEffortReleaseReason(client, project.project().id(), worker.name());
                return "cancelled";
            } catch (ExternalCliWorkerAdapter.QuotaExceededException exc) {
                recordTaskEvent(client, project.project().id(), "reason", "reason", worker.name(), "task_quota_exceeded", "reason", "quota", exc.getMessage());
                bestEffortReleaseReason(client, project.project().id(), worker.name());
                bestEffortStopProject(client, project.project().id(), exc.getMessage());
                return "quota_exceeded";
            } catch (ExternalCliWorkerAdapter.RetryableWorkerException exc) {
                recordTaskEvent(client, project.project().id(), "reason", "reason", worker.name(), "task_retryable_error", "reason", "retry", exc.getMessage());
                bestEffortReleaseReason(client, project.project().id(), worker.name());
                return "unhealthy";
            } catch (Exception exc) {
                recordTaskEvent(client, project.project().id(), "reason", "reason", worker.name(), "task_failed", "reason", "error", exc.toString());
                bestEffortReleaseReason(client, project.project().id(), worker.name());
                return "failed";
            } finally {
                lease.stop();
        }
    }

    private void reapFutures(DispatcherClient client) {
        List<Future<String>> done = futures.keySet().stream().filter(Future::isDone).toList();
        for (Future<String> future : done) {
            RunningTask task = futures.remove(future);
            stalledTaskReports.remove(taskKey(task));
            stalledTaskRecoveries.remove(taskKey(task));
            try {
                String outcome = future.get();
                if ("unhealthy".equals(outcome)) {
                    workerUnhealthyUntil.put(task.workerName(), System.currentTimeMillis() + UNHEALTHY_RETRY_AFTER_SECONDS * 1000L);
                    LOG.info("worker marked unhealthy worker={} retry_after={}s", task.workerName(), UNHEALTHY_RETRY_AFTER_SECONDS);
                } else {
                    workerUnhealthyUntil.remove(task.workerName());
                }
                String rejectedKey = rejectedKey(task.projectId(), task.taskType(), task.workerName());
                if ("rejected".equals(outcome)) {
                    workerRejectedUntil.put(rejectedKey, System.currentTimeMillis() + REJECTED_RETRY_AFTER_SECONDS * 1000L);
                    LOG.info("worker marked rejected project={} task={} worker={} retry_after={}s", task.projectId(), task.taskType(), task.workerName(), REJECTED_RETRY_AFTER_SECONDS);
                } else {
                    workerRejectedUntil.remove(rejectedKey);
                }
                if ("success".equals(outcome) && "reason".equals(task.taskType()) && task.factCount() != null && task.hintCount() != null && task.openIntentCount() != null) {
                    reasonCheckpoints.put(task.projectId(), new ReasonCheckpoint(task.factCount(), task.hintCount(), task.openIntentCount()));
                    reasonFailureCounts.remove(reasonFailureKey(task));
                } else if ("reason".equals(task.taskType()) && retryableReasonFailure(outcome)) {
                    recordReasonFailure(client, task, outcome);
                }
                if ("explore".equals(task.taskType()) && task.intentId() != null) {
                    if ("success".equals(outcome)) {
                        intentFailureCounts.remove(intentFailureKey(task.projectId(), task.intentId()));
                        blockedIntentFailures.remove(intentFailureKey(task.projectId(), task.intentId()));
                    } else if (retryableTaskFailure(outcome)) {
                        recordIntentFailure(client, task, outcome);
                    }
                }
            } catch (Exception exc) {
                LOG.warn("task crashed project={} task={} worker={} error={}", task.projectId(), task.taskType(), task.workerName(), exc.toString());
            }
        }
    }

    private boolean retryableReasonFailure(String outcome) {
        return outcome == null
                || "failed".equals(outcome)
                || "cancelled".equals(outcome)
                || "unhealthy".equals(outcome)
                || "rejected".equals(outcome);
    }

    private boolean retryableTaskFailure(String outcome) {
        return outcome == null
                || "failed".equals(outcome)
                || "cancelled".equals(outcome)
                || "unhealthy".equals(outcome)
                || "rejected".equals(outcome);
    }

    private void recordIntentFailure(DispatcherClient client, RunningTask task, String outcome) {
        String key = intentFailureKey(task.projectId(), task.intentId());
        int count = intentFailureCounts.getOrDefault(key, 0) + 1;
        intentFailureCounts.put(key, count);
        if (count < INTENT_FAILURE_BLOCK_THRESHOLD || !blockedIntentFailures.add(key)) {
            return;
        }
        recordTaskEvent(client, task.projectId(), "intent", task.intentId(), task.workerName(),
                "intent_retry_blocked", task.phase(), "failure_threshold",
                "intent skipped after " + count + " consecutive failures; create a narrower intent or add a hint to continue. outcome=" + outcome);
        LOG.warn("intent retry blocked project={} intent={} worker={} count={} outcome={}",
                task.projectId(), task.intentId(), task.workerName(), count, outcome);
    }

    private void recordReasonFailure(DispatcherClient client, RunningTask task, String outcome) {
        String key = reasonFailureKey(task);
        int count = reasonFailureCounts.getOrDefault(key, 0) + 1;
        reasonFailureCounts.put(key, count);
        if (count < REASON_FAILURE_CHECKPOINT_THRESHOLD
                || task.factCount() == null
                || task.hintCount() == null
                || task.openIntentCount() == null) {
            return;
        }
        reasonCheckpoints.put(task.projectId(), new ReasonCheckpoint(task.factCount(), task.hintCount(), task.openIntentCount()));
        reasonFailureCounts.remove(key);
        recordTaskEvent(client, task.projectId(), "reason", "reason", task.workerName(),
                "reason_trigger_checkpointed", task.phase(), "failure_threshold",
                "reason trigger skipped after " + count + " consecutive failures"
                        + (task.reasonTrigger() == null ? "" : " trigger=" + task.reasonTrigger())
                        + " outcome=" + outcome);
        LOG.warn("reason trigger checkpointed after failures project={} worker={} count={} trigger={} outcome={}",
                task.projectId(), task.workerName(), count, task.reasonTrigger(), outcome);
    }

    private String reasonFailureKey(RunningTask task) {
        return task.projectId() + "|" + task.workerName() + "|" + (task.reasonTrigger() == null ? "" : task.reasonTrigger());
    }

    private String intentFailureKey(String projectId, String intentId) {
        return projectId + "|" + intentId;
    }

    private void recordTaskEvent(DispatcherClient client, String projectId, String scopeType, String scopeId, String worker,
                                 String eventType, String phase, String step, String message) {
        try {
            client.appendRuntimeEvent(projectId, new CreateRuntimeEventRequest(
                    scopeType,
                    scopeId,
                    worker,
                    eventType,
                    phase,
                    step,
                    message,
                    null,
                    null,
                    null,
                    null
            ));
        } catch (Exception exc) {
            LOG.debug("runtime event append failed project={} scope={} scope_id={} event={} error={}",
                    projectId, scopeType, scopeId, eventType, exc.toString());
        }
    }

    private void initializeReasonCheckpoints(List<ProjectSummary> summaries) {
        for (ProjectSummary summary : summaries) {
            if (!"active".equals(summary.status()) || reasonCheckpoints.containsKey(summary.id())) {
                continue;
            }
            int openIntentCount = summary.working_intent_count() + summary.unclaimed_intent_count();
            if (openIntentCount == 0) {
                continue;
            }
            reasonCheckpoints.put(summary.id(), new ReasonCheckpoint(summary.fact_count(), summary.hint_count(), openIntentCount));
        }
    }

    private void refreshRuntimeProjects(List<ProjectSummary> summaries) {
        Set<String> activeIds = new HashSet<>();
        for (ProjectSummary summary : summaries) {
            if ("active".equals(summary.status())) {
                activeIds.add(summary.id());
            }
        }
        runtimeProjectIds.clear();
        runtimeProjectIds.addAll(runningProjectIds());
        runtimeProjectIds.retainAll(activeIds);
        Set<String> runningTaskKeys = futures.values().stream().map(this::taskKey).collect(java.util.stream.Collectors.toSet());
        stalledTaskReports.retainAll(runningTaskKeys);
        stalledTaskRecoveries.retainAll(runningTaskKeys);
        for (String projectId : new ArrayList<>(inactiveCleanupDone.keySet())) {
            String status = summaries.stream().filter(summary -> summary.id().equals(projectId)).map(ProjectSummary::status).findFirst().orElse(null);
            if (!inactiveCleanupDone.get(projectId).equals(status)) {
                inactiveCleanupDone.remove(projectId);
            }
        }
    }

    private void cancelInactiveTasks(List<ProjectSummary> summaries) {
        Map<String, String> statuses = new HashMap<>();
        for (ProjectSummary summary : summaries) {
            statuses.put(summary.id(), summary.status());
        }
        for (Map.Entry<Future<String>, RunningTask> entry : futures.entrySet()) {
            String status = statuses.getOrDefault(entry.getValue().projectId(), "deleted");
            if (!"active".equals(status) && entry.getValue().cancellation().cancel(status)) {
                entry.getKey().cancel(true);
            }
        }
    }

    private void queueContainerCleanups(DispatchConfig config, List<ProjectSummary> summaries) {
        DockerCli docker = docker(config);
        queueCompletedCleanups(docker, summaries);
        queueStoppedCleanups(docker, summaries);
        queueOrphanCleanups(docker, summaries);
    }

    private void queueCompletedCleanups(DockerCli docker, List<ProjectSummary> summaries) {
        for (ProjectSummary summary : summaries) {
            if (!"completed".equals(summary.status()) || summary.id() == null) {
                continue;
            }
            if (inactiveCleanupDone.containsKey(summary.id())) {
                continue;
            }
            String containerName = docker.containerName(summary.id());
            if (cleanupPending.contains(containerName) || runningProjectIds().contains(summary.id())) {
                continue;
            }
            Future<Boolean> future = cleanupExecutor.submit(() -> docker.cleanupCompleted(summary.id()));
            cleanupFutures.put(future, new CleanupTask(containerName, summary.id(), "completed"));
            cleanupPending.add(containerName);
        }
    }

    private void queueStoppedCleanups(DockerCli docker, List<ProjectSummary> summaries) {
        for (ProjectSummary summary : summaries) {
            if (!"stopped".equals(summary.status()) || summary.id() == null) {
                continue;
            }
            if (inactiveCleanupDone.containsKey(summary.id())) {
                continue;
            }
            String containerName = docker.containerName(summary.id());
            if (cleanupPending.contains(containerName) || runningProjectIds().contains(summary.id())) {
                continue;
            }
            Future<Boolean> future = cleanupExecutor.submit(() -> docker.cleanupStopped(summary.id()));
            cleanupFutures.put(future, new CleanupTask(containerName, summary.id(), "stopped"));
            cleanupPending.add(containerName);
        }
    }

    private void queueOrphanCleanups(DockerCli docker, List<ProjectSummary> summaries) {
        Set<String> expected = new HashSet<>();
        for (ProjectSummary summary : summaries) {
            expected.add(docker.containerName(summary.id()));
        }
        for (String name : docker.managedContainerNames()) {
            if (expected.contains(name) || cleanupPending.contains(name)) {
                continue;
            }
            Future<Boolean> future = cleanupExecutor.submit(() -> docker.cleanupOrphan(name));
            cleanupFutures.put(future, new CleanupTask(name, null, null));
            cleanupPending.add(name);
        }
    }

    private void reapCleanupFutures() {
        List<Future<Boolean>> done = cleanupFutures.keySet().stream().filter(Future::isDone).toList();
        for (Future<Boolean> future : done) {
            CleanupTask task = cleanupFutures.remove(future);
            cleanupPending.remove(task.containerName());
            try {
                boolean success = future.get();
                if (success && task.projectId() != null && task.targetStatus() != null) {
                    inactiveCleanupDone.put(task.projectId(), task.targetStatus());
                } else if (task.projectId() != null) {
                    inactiveCleanupDone.remove(task.projectId());
                }
            } catch (Exception exc) {
                if (task.projectId() != null) {
                    inactiveCleanupDone.remove(task.projectId());
                }
            }
        }
    }

    private WorkerSelection selectWorker(DispatchConfig config, String projectId, String taskType) {
        long now = System.currentTimeMillis();
        List<DispatchConfig.WorkerConfig> candidates = new ArrayList<>();
        List<String> blockedBusy = new ArrayList<>();
        List<String> blockedUnhealthy = new ArrayList<>();
        List<String> blockedRejected = new ArrayList<>();
        List<String> blockedTaskType = new ArrayList<>();
        Map<String, Integer> runningCounts = workerCounts();
        for (DispatchConfig.WorkerConfig worker : config.workers()) {
            if (!worker.supports(taskType)) {
                blockedTaskType.add(worker.name());
                continue;
            }
            int running = runningCounts.getOrDefault(worker.name(), 0);
            if (running >= Math.max(1, worker.max_running())) {
                blockedBusy.add(worker.name() + "(" + running + "/" + worker.max_running() + ")");
                continue;
            }
            long unhealthyUntil = workerUnhealthyUntil.getOrDefault(worker.name(), 0L);
            if (unhealthyUntil > now) {
                blockedUnhealthy.add(worker.name());
                continue;
            }
            long rejectedUntil = workerRejectedUntil.getOrDefault(rejectedKey(projectId, taskType, worker.name()), 0L);
            if (rejectedUntil > now) {
                blockedRejected.add(worker.name());
                continue;
            }
            candidates.add(worker);
        }
        if (candidates.isEmpty()) {
            return new WorkerSelection(null, blockedBusy, blockedUnhealthy, blockedRejected, blockedTaskType);
        }
        candidates.sort(Comparator
                .comparingInt(DispatchConfig.WorkerConfig::priority)
                .thenComparingInt(worker -> runningCounts.getOrDefault(worker.name(), 0))
                .thenComparingInt(worker -> ThreadLocalRandom.current().nextInt(1000)));
        return new WorkerSelection(candidates.get(0), blockedBusy, blockedUnhealthy, blockedRejected, blockedTaskType);
    }

    private Map<String, Integer> workerCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (RunningTask task : futures.values()) {
            counts.put(task.workerName(), counts.getOrDefault(task.workerName(), 0) + 1);
        }
        return counts;
    }

    private int projectRunningTaskCount(String projectId) {
        int count = 0;
        for (RunningTask task : futures.values()) {
            if (projectId.equals(task.projectId())) {
                count++;
            }
        }
        return count;
    }

    private boolean canScheduleCostClass(String projectId, String costClass) {
        if (!"heavy".equals(costClass)) {
            return true;
        }
        for (RunningTask task : futures.values()) {
            if (!projectId.equals(task.projectId()) && "heavy".equals(task.costClass())) {
                return false;
            }
        }
        return true;
    }

    private int phaseCostPriority(String costClass) {
        return switch (costClass == null ? "" : costClass) {
            case "light" -> 0;
            case "medium" -> 1;
            case "heavy" -> 2;
            default -> 3;
        };
    }

    private boolean projectHasRunningBootstrap(String projectId) {
        return futures.values().stream().anyMatch(task -> projectId.equals(task.projectId()) && "bootstrap".equals(task.taskType()));
    }

    private Set<String> projectRunningExploreIntents(String projectId) {
        Set<String> ids = new HashSet<>();
        for (RunningTask task : futures.values()) {
            if (projectId.equals(task.projectId()) && "explore".equals(task.taskType()) && task.intentId() != null) {
                ids.add(task.intentId());
            }
        }
        return ids;
    }

    private int runningProjectCount(List<ProjectSummary> summaries) {
        Set<String> activeIds = new HashSet<>();
        for (ProjectSummary summary : summaries) {
            if ("active".equals(summary.status())) {
                activeIds.add(summary.id());
            }
        }
        Set<String> copy = new HashSet<>(runtimeProjectIds);
        copy.retainAll(activeIds);
        return copy.size();
    }

    private Set<String> runningProjectIds() {
        Set<String> ids = new HashSet<>();
        for (RunningTask task : futures.values()) {
            ids.add(task.projectId());
        }
        return ids;
    }

    private List<ProjectSummary> orderedProjects(List<ProjectSummary> summaries) {
        if (summaries.isEmpty()) {
            return summaries;
        }
        List<ProjectSummary> ordered = summaries.stream().sorted(Comparator.comparing(ProjectSummary::id)).toList();
        int offset = projectCursor % ordered.size();
        projectCursor++;
        List<ProjectSummary> rotated = new ArrayList<>(ordered.size());
        rotated.addAll(ordered.subList(offset, ordered.size()));
        rotated.addAll(ordered.subList(0, offset));
        return rotated;
    }

    private boolean isInitialProject(ProjectDetail project) {
        Set<String> factIds = new HashSet<>();
        for (Fact fact : project.facts()) {
            factIds.add(fact.id());
        }
        if (!factIds.equals(Set.of("origin", "goal")) || project.facts().size() != 2) {
            return false;
        }
        if (project.intents().isEmpty()) {
            return true;
        }
        return project.intents().stream().allMatch(this::isBootstrapIntent);
    }

    private boolean projectRequiresBootstrap(ProjectDetail project, DispatchConfig config) {
        if (!project.project().bootstrap_enabled()) {
            return false;
        }
        if (getBootstrapIntent(project) != null) {
            return true;
        }
        return config.workers().stream().anyMatch(worker -> worker.supports("bootstrap"));
    }

    private Intent getBootstrapIntent(ProjectDetail project) {
        Comparator<Intent> comparator = Comparator
                .comparing((Intent intent) -> intent.worker() != null)
                .thenComparing(Intent::created_at)
                .thenComparing(Intent::id);
        return project.intents().stream()
                .filter(this::isBootstrapIntent)
                .sorted(comparator)
                .findFirst()
                .orElse(null);
    }

    private boolean isBootstrapIntent(Intent intent) {
        return BOOTSTRAP_INTENT_DESCRIPTION.equals(intent.description())
                && BOOTSTRAP_INTENT_CREATOR.equals(intent.creator())
                && intent.to() == null
                && intent.from().equals(List.of("origin"));
    }

    private Intent createBootstrapIntent(DispatcherClient client, String projectId) {
        try {
            return client.createIntent(projectId, List.of("origin"), BOOTSTRAP_INTENT_DESCRIPTION, BOOTSTRAP_INTENT_CREATOR);
        } catch (RuntimeException exc) {
            return null;
        }
    }

    private int projectOpenIntentCount(ProjectDetail project) {
        int count = 0;
        for (Intent intent : project.intents()) {
            if (intent.to() == null) {
                count++;
            }
        }
        return count;
    }

    private void detectStalledTasks(DispatchConfig config, DispatcherClient client) {
        List<Map.Entry<Future<String>, RunningTask>> entries = new ArrayList<>(futures.entrySet());
        for (Map.Entry<Future<String>, RunningTask> entry : entries) {
            RunningTask task = entry.getValue();
            if (isTaskStalled(task)) {
                String key = taskKey(task);
                if (stalledTaskReports.add(key)) {
                    String scopeType = "reason".equals(task.taskType()) ? "reason" : "intent";
                    String scopeId = "reason".equals(task.taskType()) ? "reason" : task.intentId();
                    recordTaskEvent(client, task.projectId(), scopeType, scopeId, task.workerName(),
                            "task_stalled_detected", task.phase(), "stalled",
                            "task exceeded " + stalledThresholdSeconds(task.costClass()) + "s without completion [cost=" + task.costClass() + "]");
                    LOG.warn("task stalled project={} task={} phase={} cost={} worker={}",
                            task.projectId(), task.taskType(), task.phase(), task.costClass(), task.workerName());
                }
                if (stalledTaskRecoveries.add(key)) {
                    recoverStalledTask(client, entry.getKey(), task);
                }
            }
        }
    }

    private void refreshTaskArtifactProgress(DispatchConfig config, DispatcherClient client) {
        List<Map.Entry<Future<String>, RunningTask>> entries = new ArrayList<>(futures.entrySet());
        for (Map.Entry<Future<String>, RunningTask> entry : entries) {
            RunningTask task = entry.getValue();
            ArtifactProgress progress = scanArtifactProgress(config, task.projectId(), task.artifactType());
            if (progress == null) {
                continue;
            }
            boolean countChanged = task.artifactCount() == null || progress.count() > task.artifactCount();
            boolean artifactTimeChanged = progress.lastArtifactAt() != null
                    && !progress.lastArtifactAt().equals(task.lastArtifactAt());
            if (!countChanged && !artifactTimeChanged) {
                continue;
            }
            String now = Instant.now().toString();
            RunningTask updated = new RunningTask(
                    task.projectId(),
                    task.taskType(),
                    task.workerName(),
                    task.cancellation(),
                    task.intentId(),
                    task.phase(),
                    task.costClass(),
                    task.status(),
                    task.startedAt(),
                    now,
                    task.retryCount(),
                    task.sessionId(),
                    progress.type(),
                    progress.count(),
                    progress.lastArtifactAt(),
                    task.factCount(),
                    task.hintCount(),
                    task.openIntentCount(),
                    task.reasonTrigger()
            );
            futures.put(entry.getKey(), updated);
            stalledTaskReports.remove(taskKey(updated));
            stalledTaskRecoveries.remove(taskKey(updated));
            String scopeType = "reason".equals(updated.taskType()) ? "reason" : "intent";
            String scopeId = "reason".equals(updated.taskType()) ? "reason" : updated.intentId();
            recordTaskEvent(client, updated.projectId(), scopeType, scopeId, updated.workerName(),
                    "task_progress_detected", updated.phase(), "artifact_progress",
                    "artifact progress detected: type=" + progress.type() + " count=" + progress.count()
                            + (progress.lastArtifactPath() == null ? "" : " latest=" + progress.lastArtifactPath()));
        }
    }

    private boolean isTaskStalled(RunningTask task) {
        String startedAt = task.lastProgressAt() != null && !task.lastProgressAt().isBlank() ? task.lastProgressAt() : task.startedAt();
        if (startedAt == null || startedAt.isBlank()) {
            return false;
        }
        try {
            Instant instant = Instant.parse(startedAt);
            return instant.plusSeconds(stalledThresholdSeconds(task.costClass())).isBefore(Instant.now());
        } catch (Exception ignored) {
            return false;
        }
    }

    private int stalledThresholdSeconds(String costClass) {
        return switch (costClass == null ? "" : costClass) {
            case "light" -> STALLED_LIGHT_SECONDS;
            case "medium" -> STALLED_MEDIUM_SECONDS;
            default -> STALLED_HEAVY_SECONDS;
        };
    }

    private String classifyReasonPhase(ProjectDetail project) {
        StringBuilder text = new StringBuilder();
        if (project == null) {
            return "java_recon";
        }
        if (project.facts() != null) {
            for (Fact fact : project.facts()) {
                text.append(fact.description()).append(' ');
            }
        }
        if (project.hints() != null) {
            project.hints().forEach(hint -> text.append(hint.content()).append(' '));
        }
        if (project.intents() != null) {
            project.intents().forEach(intent -> text.append(intent.description()).append(' '));
        }
        return classifyPhaseText(text.toString(), true);
    }

    private String classifyIntentPhase(Intent intent) {
        String text = intent == null ? "" : intent.description();
        return classifyPhaseText(text, false);
    }

    private String phaseForIntent(Intent intent) {
        return classifyIntentPhase(intent);
    }

    private String classifyPhaseText(String text, boolean reasonPhase) {
        String normalized = text == null ? "" : text;
        if (REPORT_PHASE_PATTERN.matcher(normalized).find()) {
            return "report";
        }
        if (VERIFY_PHASE_PATTERN.matcher(normalized).find()) {
            return "verify";
        }
        if (CONSTANT_PHASE_PATTERN.matcher(normalized).find()) {
            return "constant_recovery";
        }
        if (JNI_PHASE_PATTERN.matcher(normalized).find()) {
            return "jni_map";
        }
        if (SIGNATURE_PHASE_PATTERN.matcher(normalized).find()) {
            return "signature_check";
        }
        if (NATIVE_PHASE_PATTERN.matcher(normalized).find()) {
            return reasonPhase ? "native_entry" : "native_transform";
        }
        if (MANIFEST_PHASE_PATTERN.matcher(normalized).find()) {
            return "apk_recon";
        }
        if (RESOURCE_PHASE_PATTERN.matcher(normalized).find()) {
            return "java_recon";
        }
        return reasonPhase ? "java_recon" : "candidate_reconstruct";
    }

    private String costClassForPhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return "medium";
        }
        return switch (phase) {
            case "bootstrap", "race", "native_entry", "native_transform", "constant_recovery", "candidate_reconstruct", "verify" -> "heavy";
            case "apk_recon", "jni_map", "signature_check", "report" -> "medium";
            default -> "light";
        };
    }

    private String taskKey(RunningTask task) {
        return task.projectId() + "|" + task.taskType() + "|" + (task.intentId() == null ? "reason" : task.intentId()) + "|" + task.workerName() + "|" + task.startedAt();
    }

    private void recoverStalledTask(DispatcherClient client, Future<String> future, RunningTask task) {
        RecoveryPlan plan = recoveryPlanForPhase(task.phase(), task.artifactType());
        String scopeType = "reason".equals(task.taskType()) ? "reason" : "intent";
        String scopeId = "reason".equals(task.taskType()) ? "reason" : task.intentId();
        recordTaskEvent(client, task.projectId(), scopeType, scopeId, task.workerName(),
                "task_recovery_scheduled", task.phase(), "recovery",
                "stalled recovery scheduled: " + plan.label() + " [cost=" + task.costClass() + "]");
        if (plan.preserveArtifacts()) {
            bestEffortPreserveRecoveryArtifacts(client, task);
        }
        bestEffortCreateRecoveryHint(client, task, plan);
        if (task.cancellation().cancel("stalled_recovery")) {
            future.cancel(true);
        }
        if ("reason".equals(task.taskType())) {
            bestEffortReleaseReason(client, task.projectId(), task.workerName());
        } else if (task.intentId() != null) {
            bestEffortRelease(client, task.projectId(), task.intentId(), task.workerName());
        }
        workerUnhealthyUntil.put(task.workerName(), System.currentTimeMillis() + plan.cooldownSeconds() * 1000L);
        recordTaskEvent(client, task.projectId(), scopeType, scopeId, task.workerName(),
                "task_recovery_applied", task.phase(), "recovery",
                "stalled recovery applied: " + plan.label());
        LOG.warn("stalled recovery applied project={} task={} phase={} worker={} cooldown={}s strategy={}",
                task.projectId(), task.taskType(), task.phase(), task.workerName(), plan.cooldownSeconds(), plan.label());
    }

    private void bestEffortCreateRecoveryHint(DispatcherClient client, RunningTask task, RecoveryPlan plan) {
        try {
            client.createHint(
                    task.projectId(),
                    plan.hintMessage(task),
                    "dispatcher.recovery"
            );
        } catch (Exception exc) {
            LOG.debug("recovery hint create failed project={} task={} error={}", task.projectId(), task.taskType(), exc.toString());
        }
    }

    private void bestEffortPreserveRecoveryArtifacts(DispatcherClient client, RunningTask task) {
        try {
            DispatchConfig config = loadConfig(properties.getDispatcher().getConfig());
            persistProjectExports(config, client, task.projectId());
            snapshotProjectWorkspace(config, task.projectId());
            String scopeType = "reason".equals(task.taskType()) ? "reason" : "intent";
            String scopeId = "reason".equals(task.taskType()) ? "reason" : task.intentId();
            recordTaskEvent(client, task.projectId(), scopeType, scopeId, task.workerName(),
                    "task_recovery_artifacts_preserved", task.phase(), "recovery_artifacts",
                    "recovery preserved exports and workspace snapshot before redispatch");
        } catch (Exception exc) {
            LOG.debug("recovery artifact preservation failed project={} task={} error={}", task.projectId(), task.taskType(), exc.toString());
        }
    }

    private RecoveryPlan recoveryPlanForPhase(String phase, String artifactType) {
        String normalized = phase == null ? "" : phase;
        return switch (normalized) {
            case "jni_map" -> new RecoveryPlan(
                    "refresh snapshot and retry JNI mapping",
                    STALLED_RECOVERY_COOLDOWN_SECONDS,
                    false,
                    task -> "Dispatcher auto-recovery: phase `" + task.phase() + "` stalled while focusing `" + task.artifactType()
                            + "`. Refresh workspace snapshot/JADX search inputs and retry JNI bridge mapping on redispatch."
            );
            case "apk_recon", "java_recon", "signature_check" -> new RecoveryPlan(
                    "refresh lightweight analysis context",
                    STALLED_RECOVERY_COOLDOWN_SECONDS,
                    false,
                    task -> "Dispatcher auto-recovery: phase `" + task.phase() + "` stalled while focusing `" + task.artifactType()
                            + "`. Re-dispatching with refreshed snapshot/search context; review manifest/java findings if it stalls again."
            );
            case "native_entry", "native_transform", "constant_recovery" -> new RecoveryPlan(
                    "preserve native artifacts and cool down worker",
                    STALLED_RECOVERY_NATIVE_COOLDOWN_SECONDS,
                    true,
                    task -> "Dispatcher auto-recovery: native-heavy phase `" + task.phase() + "` stalled while focusing `" + task.artifactType()
                            + "`. Preserving current exports/snapshots before redispatch; next attempt should reuse trace/native artifacts instead of restarting blind."
            );
            case "candidate_reconstruct", "verify" -> new RecoveryPlan(
                    "preserve candidate context and retry verification",
                    STALLED_RECOVERY_VERIFY_COOLDOWN_SECONDS,
                    true,
                    task -> "Dispatcher auto-recovery: phase `" + task.phase() + "` stalled while focusing `" + task.artifactType()
                            + "`. Preserving current candidate/trace artifacts before redispatch; next attempt should verify from latest outputs first."
            );
            case "report" -> new RecoveryPlan(
                    "retry report generation from preserved exports",
                    STALLED_RECOVERY_COOLDOWN_SECONDS,
                    true,
                    task -> "Dispatcher auto-recovery: report phase stalled. Preserving current exports and redispatching so the next attempt can continue from existing material."
            );
            default -> new RecoveryPlan(
                    "release and retry",
                    STALLED_RECOVERY_COOLDOWN_SECONDS,
                    false,
                    task -> "Dispatcher auto-recovery: phase `" + task.phase() + "` on worker `" + task.workerName()
                            + "` stalled while focusing `" + task.artifactType()
                            + "`. Re-dispatching task for another attempt; review recent runtime events and artifacts if it stalls again."
            );
        };
    }

    private ArtifactProgress scanArtifactProgress(DispatchConfig config, String projectId) {
        return scanArtifactProgress(config, projectId, null);
    }

    private ArtifactProgress scanArtifactProgress(DispatchConfig config, String projectId, String preferredType) {
        Path outputDir = analysisOutputDir(config, projectId);
        if (outputDir == null || !Files.isDirectory(outputDir)) {
            return null;
        }
        int totalCount = 0;
        int preferredCount = 0;
        FileTime latestTime = null;
        String latestPath = null;
        FileTime latestPreferredTime = null;
        String latestPreferredPath = null;
        try (Stream<Path> walk = Files.walk(outputDir)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                totalCount++;
                String artifactType = artifactType(outputDir, path);
                try {
                    FileTime fileTime = Files.getLastModifiedTime(path);
                    if (latestTime == null || fileTime.compareTo(latestTime) > 0) {
                        latestTime = fileTime;
                        latestPath = outputDir.relativize(path).toString().replace('\\', '/');
                    }
                    if (preferredType != null && preferredType.equals(artifactType)) {
                        preferredCount++;
                        if (latestPreferredTime == null || fileTime.compareTo(latestPreferredTime) > 0) {
                            latestPreferredTime = fileTime;
                            latestPreferredPath = outputDir.relativize(path).toString().replace('\\', '/');
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        if (preferredType != null && preferredCount > 0) {
            return new ArtifactProgress(preferredType, preferredCount,
                    latestPreferredTime == null ? null : Instant.ofEpochMilli(latestPreferredTime.toMillis()).toString(),
                    latestPreferredPath);
        }
        return new ArtifactProgress("all", totalCount, latestTime == null ? null : Instant.ofEpochMilli(latestTime.toMillis()).toString(), latestPath);
    }

    private String preferredArtifactTypeForPhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return "all";
        }
        return switch (phase) {
            case "apk_recon", "java_recon", "jni_map", "signature_check" -> "snapshot";
            case "native_entry", "native_transform", "constant_recovery", "candidate_reconstruct", "verify" -> "trace";
            case "report" -> "report";
            case "bootstrap" -> "metadata";
            default -> "all";
        };
    }

    private String artifactType(Path outputDir, Path path) {
        String relative = outputDir.relativize(path).toString().replace('\\', '/').toLowerCase();
        String name = path.getFileName().toString().toLowerCase();
        if (relative.startsWith("llm-traces/") || relative.contains("/llm-traces/")) return "trace";
        if (relative.startsWith("llm-counts/") || relative.contains("/llm-counts/")) return "count";
        if (relative.startsWith("workspace-snapshot/") || relative.contains("/workspace-snapshot/")) return "snapshot";
        if (name.endsWith(".md")) return "report";
        if (name.endsWith(".json") || name.endsWith(".jsonl") || name.endsWith(".yaml") || name.endsWith(".yml")) return "metadata";
        if (name.endsWith(".txt")) return "text";
        if (name.endsWith(".bin")) return "binary";
        if (name.endsWith(".so")) return "native";
        return "file";
    }

    private String reasonTrigger(ProjectDetail project) {
        int openIntentCount = projectOpenIntentCount(project);
        String phase = classifyReasonPhase(project);
        ReasonCheckpoint checkpoint = reasonCheckpoints.get(project.project().id());
        if (checkpoint == null) {
            return "report".equals(phase) ? "report" : "initial";
        }
        List<String> changes = reasonCheckpointChanges(project, checkpoint, openIntentCount);
        if ("report".equals(phase)) {
            return changes.isEmpty() ? null : "report";
        }
        return changes.isEmpty() ? null : String.join(",", changes);
    }

    private List<String> reasonCheckpointChanges(ProjectDetail project, ReasonCheckpoint checkpoint, int openIntentCount) {
        List<String> changes = new ArrayList<>();
        if (project.facts().size() > checkpoint.factCount()) {
            changes.add("facts:" + checkpoint.factCount() + "->" + project.facts().size());
        }
        if (project.hints().size() > checkpoint.hintCount()) {
            changes.add("hints:" + checkpoint.hintCount() + "->" + project.hints().size());
        }
        if (checkpoint.openIntentCount() > 0 && openIntentCount == 0) {
            changes.add("open_intents:" + checkpoint.openIntentCount() + "->0");
        }
        return changes;
    }

    private int intentPriority(Intent intent, PhaseRuntimeState state) {
        if (state == null) {
            return 1;
        }
        if (state.stalled()) {
            return 0;
        }
        String stop = state.latest_stop_condition() == null ? "" : state.latest_stop_condition().toLowerCase();
        if (stop.contains("pending")) {
            return 0;
        }
        return 1;
    }

    private void bestEffortRelease(DispatcherClient client, String projectId, String intentId, String workerName) {
        DispatcherClient.ApiResult result = client.release(projectId, intentId, workerName);
        if (result.statusCode() != 403 && result.statusCode() != 409 && !result.ok()) {
            LOG.warn("release failed project={} intent={} worker={} status={}", projectId, intentId, workerName, result.statusCode());
        }
    }

    private void bestEffortReleaseReason(DispatcherClient client, String projectId, String workerName) {
        DispatcherClient.ApiResult result = client.releaseReason(projectId, workerName);
        if (result.statusCode() != 403 && result.statusCode() != 409 && !result.ok()) {
            LOG.warn("reason release failed project={} worker={} status={}", projectId, workerName, result.statusCode());
        }
    }

    private void recordReasonHintNeeded(DispatcherClient client, String projectId, String workerName, String phase, String message) {
        recordTaskEvent(client, projectId, "reason", "reason", workerName, "reason_hint_needed", phase, "hint_required", message);
    }

    private void bestEffortStopProject(DispatcherClient client, String projectId, String reason) {
        try {
            client.updateProjectStatus(projectId, "stopped");
            LOG.warn("project stopped after quota error project={} reason={}", projectId, reason);
        } catch (Exception exc) {
            LOG.warn("stop project failed project={} reason={} error={}", projectId, reason, exc.toString());
        }
    }

    private void validateServerSettings(DispatchConfig config, DispatcherClient client) {
        if (settingsChecked) {
            return;
        }
        Settings settings = client.settings();
        int interval = Math.max(1, config.runtimeOrDefault().interval());
        if ((settings.intent_timeout() > 0 && settings.intent_timeout() <= interval)
                || (settings.reason_timeout() > 0 && settings.reason_timeout() <= interval)) {
            throw new IllegalStateException("server timeouts must be greater than dispatcher interval");
        }
        settingsChecked = true;
    }

    private void runStartupHealthchecksOnly(DispatchConfig config) {
        if (config.workers() == null || config.workers().isEmpty()) {
            throw new IllegalStateException("no workers configured");
        }
        for (DispatchConfig.WorkerConfig worker : config.workers()) {
            WorkerAdapters.forType(worker.type(), config.container(), config.runtimeOrDefault().prompt_group(), config.server(), docker(config))
                    .startupHealthcheck(worker, config.runtimeOrDefault().healthcheck_timeout());
            LOG.info("startup healthcheck worker={} type={} status=ok", worker.name(), worker.type());
        }
    }

    private boolean startupHealthcheckEnabled(DispatchConfig config) {
        String mode = config.runtimeOrDefault().worker_healthcheck();
        return "startup_only".equals(mode) || "startup_and_task".equals(mode);
    }

    private boolean taskHealthcheckEnabled(DispatchConfig config) {
        return "startup_and_task".equals(config.runtimeOrDefault().worker_healthcheck());
    }

    private DispatchConfig loadConfig(String path) throws IOException {
        Path configPath = Path.of(path);
        if (!Files.exists(configPath)) {
            LOG.warn("dispatch config {} not found; using built-in mock config", configPath);
            return new DispatchConfig(
                    "http://127.0.0.1:8000",
                    null,
                    new DispatchConfig.RuntimeConfig(3, 1, 1, 1, 30, "disabled", "default", null),
                    null,
                    null,
                    null,
                    List.of(new DispatchConfig.WorkerConfig("mock", "mock", List.of("bootstrap", "reason", "explore"), 1, 0, null))
            );
        }
        return mergeCommonEnv(new YAMLMapper().readValue(configPath.toFile(), DispatchConfig.class));
    }

    private DispatchConfig mergeCommonEnv(DispatchConfig raw) {
        if (raw.common_env() == null || raw.common_env().isEmpty() || raw.workers() == null) {
            return raw;
        }
        List<DispatchConfig.WorkerConfig> workers = raw.workers().stream()
                .map(worker -> new DispatchConfig.WorkerConfig(
                        worker.name(),
                        worker.type(),
                        worker.task_types(),
                        worker.max_running(),
                        worker.priority(),
                        mergedEnv(raw.common_env(), worker.env())
                ))
                .toList();
        return new DispatchConfig(raw.server(), raw.common_env(), raw.runtime(), raw.verifier(), raw.tasks(), raw.container(), workers);
    }

    private Map<String, String> mergedEnv(Map<String, String> commonEnv, Map<String, String> workerEnv) {
        Map<String, String> merged = resolveEnvPlaceholders(commonEnv);
        if (workerEnv != null) {
            merged.putAll(resolveEnvPlaceholders(workerEnv));
        }
        return merged;
    }

    private Map<String, String> resolveEnvPlaceholders(Map<String, String> env) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (env == null) {
            return resolved;
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            resolved.put(entry.getKey(), resolveEnvPlaceholder(entry.getValue()));
        }
        return resolved;
    }

    private String resolveEnvPlaceholder(String value) {
        if (value == null || value.length() < 4 || !value.startsWith("${") || !value.endsWith("}")) {
            return value;
        }
        String envKey = value.substring(2, value.length() - 1).trim();
        if (envKey.isEmpty()) {
            return value;
        }
        String resolved = System.getenv(envKey);
        return resolved == null ? value : resolved;
    }

    private String applyBootstrapResult(DispatchConfig config, ProjectDetail project, Intent intent, DispatchConfig.WorkerConfig worker, WorkerResult result, DispatcherClient client) {
        if (result instanceof WorkerResult.Rejected) {
            bestEffortRelease(client, project.project().id(), intent.id(), worker.name());
            return "rejected";
        }
        if (result instanceof WorkerResult.Fact fact) {
            client.conclude(project.project().id(), intent.id(), worker.name(), fact.description());
            return "success";
        }
        if (result instanceof WorkerResult.BootstrapComplete complete) {
            var concluded = client.conclude(project.project().id(), intent.id(), worker.name(), complete.factDescription());
            client.complete(project.project().id(), List.of(concluded.fact().id()), complete.completeDescription(), worker.name());
            persistProjectExports(config, client, project.project().id());
            snapshotProjectWorkspace(config, project.project().id());
            return "success";
        }
        if (result instanceof WorkerResult.Complete complete) {
            client.complete(project.project().id(), complete.from(), complete.description(), worker.name());
            persistProjectExports(config, client, project.project().id());
            snapshotProjectWorkspace(config, project.project().id());
            return "success";
        }
        if (result instanceof WorkerResult.Intents intents) {
            for (String description : intents.descriptions()) {
                client.createIntent(project.project().id(), List.of("origin"), description, worker.name());
            }
            bestEffortRelease(client, project.project().id(), intent.id(), worker.name());
            return "success";
        }
        return "failed";
    }

    private String applyExploreResult(DispatchConfig config, ProjectDetail project, Intent intent, DispatchConfig.WorkerConfig worker, WorkerResult result, DispatcherClient client, String phase) {
        if (result instanceof WorkerResult.Rejected) {
            bestEffortRelease(client, project.project().id(), intent.id(), worker.name());
            return "rejected";
        }
        if (result instanceof WorkerResult.Fact fact) {
            recordStopConditionEvent(client, project.project().id(), "intent", intent.id(), worker.name(), phase, stopConditionMessage(phase, fact.description(), false));
            client.conclude(project.project().id(), intent.id(), worker.name(), fact.description());
            return "success";
        }
        if (result instanceof WorkerResult.Complete complete) {
            recordStopConditionEvent(client, project.project().id(), "intent", intent.id(), worker.name(), phase, stopConditionMessage(phase, complete.description(), true));
            client.complete(project.project().id(), complete.from(), complete.description(), worker.name());
            persistProjectExports(config, client, project.project().id());
            snapshotProjectWorkspace(config, project.project().id());
            return "success";
        }
        return "failed";
    }

    private String applyReasonResult(DispatchConfig config, ProjectDetail project, DispatchConfig.WorkerConfig worker, WorkerResult result, DispatcherClient client, String phase) {
        if (result instanceof WorkerResult.Rejected) {
            bestEffortReleaseReason(client, project.project().id(), worker.name());
            return "rejected";
        }
        if (result instanceof WorkerResult.Intents intents) {
            if (intents.items().isEmpty()) {
                if (projectOpenIntentCount(project) > 0) {
                    recordStopConditionEvent(client, project.project().id(), "reason", "reason", worker.name(), phase,
                            "reason made no graph change; existing open intents cover current gap");
                    bestEffortReleaseReason(client, project.project().id(), worker.name());
                    return "success";
                }
                LOG.warn("reason created no intents and no open intents exist project={} worker={}", project.project().id(), worker.name());
                bestEffortReleaseReason(client, project.project().id(), worker.name());
                recordReasonHintNeeded(client, project.project().id(), worker.name(), phase,
                        "reason produced no new clue; add a hint to continue");
                return "success";
            }
            int maxIntents = config.tasks() == null || config.tasks().reason() == null
                    ? 2
                    : Math.max(1, config.tasks().reason().max_intents());
            List<WorkerResult.ProposedIntent> limitedIntents = intents.items().stream()
                    .limit(maxIntents)
                    .toList();
            for (WorkerResult.ProposedIntent intent : limitedIntents) {
                List<String> sources = intent.from() == null || intent.from().isEmpty() ? List.of("origin") : intent.from();
                client.createIntent(project.project().id(), sources, intent.description(), worker.name());
            }
            recordStopConditionEvent(client, project.project().id(), "reason", "reason", worker.name(), phase,
                    "reason created " + limitedIntents.size() + " follow-up intent(s); phase continues");
            bestEffortReleaseReason(client, project.project().id(), worker.name());
            return "success";
        }
        if (result instanceof WorkerResult.Complete complete) {
            recordStopConditionEvent(client, project.project().id(), "reason", "reason", worker.name(), phase, stopConditionMessage(phase, complete.description(), true));
            bestEffortReleaseReason(client, project.project().id(), worker.name());
            client.complete(project.project().id(), complete.from(), complete.description(), worker.name());
            persistProjectExports(config, client, project.project().id());
            snapshotProjectWorkspace(config, project.project().id());
            return "success";
        }
        return "failed";
    }

    private void recordStopConditionEvent(DispatcherClient client, String projectId, String scopeType, String scopeId,
                                          String workerName, String phase, String message) {
        String eventType = message.contains("met") ? "task_stop_condition_met" : "task_stop_condition_progress";
        recordTaskEvent(client, projectId, scopeType, scopeId, workerName, eventType, phase, "stop_condition", message);
    }

    private String stopConditionMessage(String phase, String description, boolean terminal) {
        String text = description == null ? "" : description;
        return switch (phase == null ? "" : phase) {
            case "jni_map" -> containsPattern(text, JNI_PHASE_PATTERN)
                    ? "stop condition met: JNI bridge identified"
                    : "stop condition pending: JNI bridge not explicit in finding";
            case "native_entry" -> containsPattern(text, COMPARE_LOOP_PATTERN)
                    ? "stop condition met: native compare/entry path identified"
                    : "stop condition pending: native entry path not explicit yet";
            case "constant_recovery" -> containsPattern(text, CONSTANT_PHASE_PATTERN)
                    ? "stop condition met: expected buffer/constant material recovered"
                    : "stop condition pending: constant recovery not explicit yet";
            case "candidate_reconstruct", "verify" -> terminal || containsPattern(text, VERIFY_PHASE_PATTERN)
                    ? "stop condition met: candidate input or verification result produced"
                    : "stop condition pending: candidate/verification output not explicit yet";
            case "signature_check" -> containsPattern(text, SIGNATURE_PHASE_PATTERN)
                    ? "stop condition met: signature/check routine identified"
                    : "stop condition pending: signature/check routine not explicit yet";
            case "apk_recon" -> containsPattern(text, MANIFEST_PHASE_PATTERN)
                    ? "stop condition met: manifest/component finding recorded"
                    : "stop condition pending: manifest/component finding not explicit yet";
            case "report" -> terminal
                    ? "stop condition met: report output completed"
                    : "stop condition pending: report output incomplete";
            default -> terminal
                    ? "stop condition met: phase produced terminal result"
                    : "stop condition progress recorded";
        };
    }

    private boolean containsPattern(String text, Pattern pattern) {
        return text != null && pattern.matcher(text).find();
    }

    private void persistProjectExports(DispatchConfig config, DispatcherClient client, String projectId) {
        Path outputDir = analysisOutputDir(config, projectId);
        if (outputDir == null) {
            return;
        }
        try {
            Files.createDirectories(outputDir);
            String json = client.export(projectId, "json");
            String yaml = client.export(projectId, "yaml");
            String timeline = client.export(projectId, "timeline");
            Files.writeString(outputDir.resolve("export.json"), json == null ? "" : json);
            Files.writeString(outputDir.resolve("export.yaml"), yaml == null ? "" : yaml);
            Files.writeString(outputDir.resolve("timeline.txt"), timeline == null ? "" : timeline);
        } catch (Exception exc) {
            LOG.warn("failed to persist project exports project={} error={}", projectId, exc.toString());
        }
    }

    private void snapshotProjectWorkspace(DispatchConfig config, String projectId) {
        Path outputDir = analysisOutputDir(config, projectId);
        if (outputDir == null) {
            return;
        }
        try {
            if (!workspaceSyncEnabled(config)) {
                return;
            }
            DockerCli docker = docker(config);
            String container = docker.containerName(projectId);
            if (!"running".equals(docker.inspectState(container))) {
                return;
            }
            Path snapshotDir = outputDir.resolve("workspace-snapshot");
            docker.copyDirectoryFromContainer(container, "/home/reverser/workspace", snapshotDir);
            writeWorkspaceManifest(snapshotDir, outputDir.resolve("workspace-files.txt"));
        } catch (Exception exc) {
            LOG.warn("failed to snapshot workspace project={} error={}", projectId, exc.toString());
        }
    }

    private boolean workspaceSyncEnabled(DispatchConfig config) {
        try {
            return new DispatcherClient(config.server() == null ? "http://127.0.0.1:8000" : config.server())
                    .settings()
                    .workspace_sync_enabled();
        } catch (Exception ignored) {
            return true;
        }
    }

    private void writeWorkspaceManifest(Path snapshotDir, Path manifestPath) throws IOException {
        if (snapshotDir == null || !Files.isDirectory(snapshotDir)) {
            return;
        }
        List<String> lines = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(snapshotDir)) {
            walk.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> lines.add(snapshotDir.relativize(path).toString()));
        }
        Files.write(manifestPath, lines);
    }

    private Path analysisOutputDir(DispatchConfig config, String projectId) {
        Path root = Path.of(System.getProperty("user.dir")).resolve("output").resolve("live-analysis");
        return projectId == null || projectId.isBlank() ? root : root.resolve(projectId);
    }

    private void closeAll(DispatcherClient client) {
        for (Map.Entry<Future<String>, RunningTask> entry : futures.entrySet()) {
            entry.getValue().cancellation().cancel("shutdown");
            entry.getKey().cancel(true);
        }
        waitForCurrentWork(client);
        shutdownExecutors();
        if (sharedDocker != null) {
            sharedDocker.close();
        }
    }

    private DockerCli docker(DispatchConfig config) {
        if (sharedDocker == null) {
            sharedDocker = new DockerCli(config.container());
        }
        return sharedDocker;
    }

    private void shutdownExecutors() {
        if (schedulerExecutor != null) {
            schedulerExecutor.shutdownNow();
        }
        if (taskExecutor != null) {
            taskExecutor.shutdown();
            try {
                taskExecutor.awaitTermination(1, TimeUnit.HOURS);
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
            }
        }
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                cleanupExecutor.awaitTermination(1, TimeUnit.HOURS);
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String rejectedKey(String projectId, String taskType, String workerName) {
        return projectId + "|" + taskType + "|" + workerName;
    }

    private record ArtifactProgress(String type, int count, String lastArtifactAt, String lastArtifactPath) {
    }

    private record VerifierIdentity(String worker, String promptGroup, String tool) {
    }

    private record RecoveryPlan(String label, int cooldownSeconds, boolean preserveArtifacts,
                                Function<RunningTask, String> hintBuilder) {
        String hintMessage(RunningTask task) {
            return hintBuilder.apply(task);
        }
    }

    private record CleanupTask(String containerName, String projectId, String targetStatus) {
    }

    /** Keeps server-side intent leases alive for every open intent claimed by a swarm worker name. */
    static final class SwarmIntentHeartbeat {
        private static final Logger LOG = LoggerFactory.getLogger(SwarmIntentHeartbeat.class);

        private final DispatcherClient client;
        private final String projectId;
        private final String workerName;
        private final int intervalSeconds;
        private final TaskCancellation cancellation;
        private final Thread thread;
        private volatile boolean stopped;

        SwarmIntentHeartbeat(DispatcherClient client, String projectId, String workerName, int intervalSeconds, TaskCancellation cancellation) {
            this.client = client;
            this.projectId = projectId;
            this.workerName = workerName;
            this.intervalSeconds = Math.max(1, intervalSeconds);
            this.cancellation = cancellation;
            this.thread = new Thread(this::runLoop, "r0re-swarm-heartbeat-" + workerName);
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void stop() {
            stopped = true;
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
            }
        }

        private void runLoop() {
            while (!stopped) {
                try {
                    Thread.sleep(intervalSeconds * 1000L);
                } catch (InterruptedException exc) {
                    if (stopped) {
                        return;
                    }
                }
                if (stopped || (cancellation != null && cancellation.isCancelled())) {
                    return;
                }
                beatOnce();
            }
        }

        void beatOnce() {
            try {
                ProjectDetail project = client.project(projectId);
                if (project == null || project.intents() == null) {
                    return;
                }
                for (Intent intent : project.intents()) {
                    if (intent.to() == null && workerName.equals(intent.worker())) {
                        client.heartbeatResult(projectId, intent.id(), workerName);
                    }
                }
            } catch (Exception exc) {
                LOG.debug("swarm intent heartbeat failed project={} worker={} error={}", projectId, workerName, exc.toString());
            }
        }
    }
}
