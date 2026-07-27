package com.fyr.r0re.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fyr.r0re.protocol.Models.Fact;
import com.fyr.r0re.protocol.Models.Intent;
import com.fyr.r0re.protocol.Models.ProjectDetail;
import com.fyr.r0re.protocol.Models.CreateRuntimeEventRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExternalCliWorkerAdapter implements WorkerAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(ExternalCliWorkerAdapter.class);
    private static final String COUNT_MODE_ENV = "R0RE_LLM_COUNT_MODE";
    private static final String GRAPH_SNAPSHOT_ROOT = "/tmp/cairn-prompts";
    private final DispatchConfig.ContainerConfig containerConfig;
    private final DockerCli docker;
    private final PromptFactory prompts;
    private final WorkerDriver driver;
    private final DispatcherClient runtimeClient;
    private final String server;
    private final JsonOutputParser parser = new JsonOutputParser();
    private final ObjectMapper mapper = new ObjectMapper();

    static final class RetryableWorkerException extends RuntimeException {
        RetryableWorkerException(String message) {
            super(message);
        }
    }

    static final class QuotaExceededException extends RuntimeException {
        QuotaExceededException(String message) {
            super(message);
        }
    }

    public ExternalCliWorkerAdapter(DispatchConfig.ContainerConfig containerConfig, String promptGroup, String workerType, String server) {
        this(containerConfig, new DockerCli(containerConfig), new PromptFactory(promptGroup), WorkerDrivers.forType(workerType), server);
    }

    ExternalCliWorkerAdapter(DispatchConfig.ContainerConfig containerConfig, DockerCli docker, PromptFactory prompts, WorkerDriver driver) {
        this(containerConfig, docker, prompts, driver, null, null);
    }

    ExternalCliWorkerAdapter(DispatchConfig.ContainerConfig containerConfig, DockerCli docker, PromptFactory prompts, WorkerDriver driver, String server) {
        this(containerConfig, docker, prompts, driver, server, null);
    }

    ExternalCliWorkerAdapter(DispatchConfig.ContainerConfig containerConfig, DockerCli docker, PromptFactory prompts, WorkerDriver driver, String server, DispatcherClient runtimeClient) {
        this.containerConfig = containerConfig;
        this.docker = docker;
        this.prompts = prompts;
        this.driver = driver;
        this.server = server == null || server.isBlank() ? "http://127.0.0.1:8000" : server;
        this.runtimeClient = runtimeClient == null ? new DispatcherClient(this.server) : runtimeClient;
    }

    @Override
    public void startupHealthcheck(DispatchConfig.WorkerConfig worker, int timeoutSeconds) {
        CountMode countMode = countMode(worker);
        if (countMode.enabled()) {
            recordCountEvent(null, worker, "startup_healthcheck", "healthcheck", null, countMode);
            return;
        }
        ProcessOutput result = docker.runEphemeral(worker.env(), driver.buildStartupHealthcheck(worker), null, Duration.ofSeconds(timeoutSeconds));
        if (result.code() != 0) {
            throw new IllegalStateException("startup healthcheck failed for " + worker.name() + " code=" + result.code() + " stderr=" + result.stderr());
        }
    }

    @Override
    public void taskHealthcheck(String projectId, DispatchConfig.WorkerConfig worker, int timeoutSeconds) {
        CountMode countMode = countMode(worker);
        if (countMode.enabled()) {
            recordCountEvent(projectId, worker, "task_healthcheck", "healthcheck", null, countMode);
            return;
        }
        String container = docker.ensureRunning(projectId);
        ProcessOutput result = docker.exec(container, execEnv(projectId, worker), driver.buildHealthcheck(worker), null, Duration.ofSeconds(timeoutSeconds));
        if (result.code() != 0) {
            throw new RetryableWorkerException("task healthcheck failed for " + worker.name() + " code=" + result.code() + " stderr=" + result.stderr());
        }
    }

    @Override
    public WorkerResult bootstrap(ProjectDetail project, DispatchConfig.WorkerConfig worker, DispatchConfig.TaskTimeout taskConfig, TaskCancellation cancellation) {
        return execute(project, worker, null, taskConfig, prompts.bootstrap(project), prompts.bootstrapConclude(project), "bootstrap", "project", project.project().id(),
                parser::parseBootstrap, parser::parseBootstrapConclude, true, cancellation);
    }

    @Override
    public WorkerResult reason(ProjectDetail project, DispatchConfig.WorkerConfig worker, DispatchConfig.ReasonTask taskConfig, TaskCancellation cancellation) {
        String container = docker.ensureRunning(project.project().id());
        String graphReference = writeGraphSnapshotReference(container, "reason_execute", exportProjectGraphYaml(project));
        // reason has no conclude fallback — Cairn never does conclude for reason tasks
        DispatchConfig.TaskTimeout timeoutConfig = taskConfig == null ? null : new DispatchConfig.TaskTimeout(taskConfig.timeout(), 0);
        return execute(project, worker, container, timeoutConfig, prompts.reason(project, taskConfig == null ? 2 : taskConfig.max_intents(), graphReference), null, "reason", "reason", "reason",
                parser::parseReason, null, false, cancellation);
    }

    @Override
    public WorkerResult explore(ProjectDetail project, Intent intent, DispatchConfig.WorkerConfig worker, DispatchConfig.TaskTimeout taskConfig, TaskCancellation cancellation) {
        String container = docker.ensureRunning(project.project().id());
        String graphYaml = exportProjectGraphYaml(project);
        String executeGraphReference = writeGraphSnapshotReference(container, "explore_execute", graphYaml);
        String concludeGraphReference = writeGraphSnapshotReference(container, "explore_conclude", graphYaml);
        return execute(project, worker, container, taskConfig, prompts.explore(project, intent, executeGraphReference), prompts.exploreConclude(project, intent, concludeGraphReference), "explore", "intent", intent.id(),
                parser::parseExplore, parser::parseExploreConclude, true, cancellation);
    }

    @Override
    public WorkerResult race(ProjectDetail project, DispatchConfig.WorkerConfig worker, String swarmWorkerName, DispatchConfig.TaskTimeout taskConfig, TaskCancellation cancellation) {
        String container = docker.ensureRunning(project.project().id());
        String graphReference = writeGraphSnapshotReference(container, "race_execute", exportProjectGraphYaml(project));
        String prompt = prompts.race(project, graphReference);
        return execute(project, worker, container, taskConfig, prompt, swarmConcludePrompt(prompt), "race", "project", project.project().id(),
                parser::parseSwarm, parser::parseSwarm, true, swarmWorkerName, cancellation);
    }

    @Override
    public WorkerResult swarmExplore(ProjectDetail project, DispatchConfig.WorkerConfig worker, String swarmWorkerName, DispatchConfig.TaskTimeout taskConfig, TaskCancellation cancellation) {
        String container = docker.ensureRunning(project.project().id());
        String graphReference = writeGraphSnapshotReference(container, "swarm_explore_execute", exportProjectGraphYaml(project));
        String prompt = prompts.swarmExplore(project, graphReference);
        // board-only workers may exit without valid terminal JSON — the tolerant parser maps that to null
        return execute(project, worker, container, taskConfig, prompt, swarmConcludePrompt(prompt), "swarm_explore", "project", project.project().id(),
                parser::parseSwarmOrNull, parser::parseSwarmOrNull, true, swarmWorkerName, cancellation);
    }

    private String swarmConcludePrompt(String prompt) {
        return prompt + "\n\n# Immediate Conclude\nYou are out of time. Stop all tool use now and return ONLY the final JSON object described in Output Requirements. Do not call any more tools.\n";
    }

    // ── Host file injection ───────────────────────────────────────────────

    private static final Pattern HOST_PATH_PATTERN = Pattern.compile(
            "(/Users/[^\\s'\"]+\\.(apk|dex|jar|so|zip|tar|gz|xz|bin))|" +
            "(/home/[^\\s'\"]+\\.(apk|dex|jar|so|zip|tar|gz|xz|bin))|" +
            "(/tmp/[^\\s'\"]+\\.(apk|dex|jar|so|zip|tar|gz|xz|bin))|" +
            "(/data/[^\\s'\"]+\\.(apk|dex|jar|so|zip|tar|gz|xz|bin))"
    );

    private static final Set<String> SKIP_EXTENSIONS = Set.of(".yml", ".yaml", ".json", ".xml", ".txt", ".md", ".py", ".sh", ".java", ".kt");

    private static final String CONTAINER_TARGET_DIR = "/home/reverser/workspace/target_files";

    /**
     * Extract host filesystem paths referenced in origin, goal, and hints.
     * Matches common binary file extensions (.apk, .dex, .jar, .so, .zip, etc.)
     * on common macOS, Linux, and Android filesystem paths.
     */
    List<Path> extractHostPaths(ProjectDetail project) {
        List<String> texts = new ArrayList<>();
        for (Fact f : project.facts()) {
            texts.add(f.description());
        }
        for (var h : project.hints()) {
            texts.add(h.content());
        }

        List<Path> paths = new ArrayList<>();
        for (String text : texts) {
            Matcher m = HOST_PATH_PATTERN.matcher(text);
            while (m.find()) {
                String matched = m.group(1) != null ? m.group(1) :
                                 m.group(2) != null ? m.group(2) :
                                 m.group(3) != null ? m.group(3) :
                                 m.group(4);
                if (matched != null) {
                    Path p = Path.of(matched);
                    String name = p.getFileName().toString().toLowerCase();
                    // Skip config/text files that aren't analysis targets
                    boolean skip = false;
                    for (String ext : SKIP_EXTENSIONS) {
                        if (name.endsWith(ext)) { skip = true; break; }
                    }
                    if (!skip && !paths.contains(p)) {
                        paths.add(p);
                    }
                }
            }
        }
        return paths;
    }

    /**
     * Copy host files referenced in the project (origin, goal, hints) into the container.
     * Called once after ensureRunning. Tracks injected files per container to avoid re-copying.
     */
    private final Map<String, Set<String>> injectedFiles = new HashMap<>();

    void copyHostFilesToContainer(ProjectDetail project, String container) {
        List<Path> hostPaths = extractHostPaths(project);
        if (hostPaths.isEmpty()) return;

        Set<String> alreadyInjected = injectedFiles.computeIfAbsent(container, k -> new java.util.HashSet<>());

        for (Path hostPath : hostPaths) {
            if (alreadyInjected.contains(hostPath.toString())) continue;
            if (!Files.exists(hostPath)) {
                LOG.warn("host file referenced in project does not exist, skipping: {}", hostPath);
                continue;
            }
            try {
                // Copy the file into the container workspace
                docker.copyFileToContainer(container, hostPath, CONTAINER_TARGET_DIR);

                // Create a symlink at the original host path inside the container
                // so that the LLM worker can access the file via the path written in origin/goal
                String hostPathStr = hostPath.toString();
                String parentDir = hostPath.getParent() != null ? hostPath.getParent().toString() : "/";
                docker.exec(container, null, List.of("/bin/sh", "-lc",
                        "mkdir -p " + shellQuote(parentDir) + " && " +
                        "ln -sf " + shellQuote(CONTAINER_TARGET_DIR + "/" + hostPath.getFileName()) + " " + shellQuote(hostPathStr)),
                        null, Duration.ofSeconds(10));

                alreadyInjected.add(hostPath.toString());
                LOG.info("injected host file into container {}: {} → {}:{} (symlink at {})",
                        container, hostPath.getFileName(), container, CONTAINER_TARGET_DIR, hostPathStr);
            } catch (Exception e) {
                LOG.warn("failed to copy host file {} into container: {}", hostPath, e.getMessage());
            }
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private WorkerResult execute(
            ProjectDetail project,
            DispatchConfig.WorkerConfig worker,
            String container,
            DispatchConfig.TaskTimeout taskConfig,
            String prompt,
            String concludePrompt,
            String phase,
            String scopeType,
            String scopeId,
            Parser primaryParser,
            Parser concludeParser,
            boolean allowConcludeFallback,
            TaskCancellation cancellation
    ) {
        return execute(project, worker, container, taskConfig, prompt, concludePrompt, phase, scopeType, scopeId,
                primaryParser, concludeParser, allowConcludeFallback, null, cancellation);
    }

    private WorkerResult execute(
            ProjectDetail project,
            DispatchConfig.WorkerConfig worker,
            String container,
            DispatchConfig.TaskTimeout taskConfig,
            String prompt,
            String concludePrompt,
            String phase,
            String scopeType,
            String scopeId,
            Parser primaryParser,
            Parser concludeParser,
            boolean allowConcludeFallback,
            String workerNameOverride,
            TaskCancellation cancellation
    ) {
        CountMode countMode = countMode(worker);
        String session = driver.prepareSession();
        DriverCommand command = driver.buildExecute(worker, prompt, session);
        if (countMode.enabled()) {
            return simulateCountOnlyExecution(project, worker, prompt, concludePrompt, phase, primaryParser, concludeParser, allowConcludeFallback, command, countMode);
        }
        String resolvedContainer = container == null || container.isBlank()
                ? docker.ensureRunning(project.project().id())
                : container;
        Instant startedAt = Instant.now();
        ProcessOutput first = null;
        ProcessOutput conclude = null;
        String resolvedSession = command.session();
        String responseText = "";
        WorkerResult parsedResult = null;
        String status = "error";
        String error = "";
        appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                "worker_execute_started", phase, "execute", "worker execution started", null, worker.type(), 1, 6);
        try {
            first = docker.exec(
                    resolvedContainer,
                    execEnv(project.project().id(), worker, workerNameOverride),
                    command.argv(),
                    null,
                    Duration.ofSeconds(timeout(taskConfig)),
                    driver::shouldStopEarly,
                    cancellationWatchdog(cancellation)
            );
            if (cancellation != null && cancellation.isCancelled()) {
                throw new java.util.concurrent.CancellationException(cancellation.reason());
            }
            resolvedSession = firstResolvedSession(command.session(), first);
            appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                    "worker_execute_response_received", phase, "execute_response", executeResponseMessage(first.code(), resolvedSession), null, worker.type(), 2, 6);

            // Detect worker-level errors even when exit code is 0 (e.g., pi reports "stopReason":"error")
            responseText = driver.extractResponseText(first.stdout(), first.stderr());
            if (containsQuotaExceeded(first.stdout(), first.stderr())) {
                appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                        "worker_quota_detected", phase, "quota", "worker quota limit detected", null, worker.type(), 2, 6);
                throw new QuotaExceededException("worker quota exceeded: " + truncateForError(first.stdout(), first.stderr()));
            }
            if (driver.isRetryableOutput(first.stdout(), first.stderr())) {
                appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                        "worker_retryable_detected", phase, "retryable", "worker returned retryable output", null, worker.type(), 2, 6);
                throw new RetryableWorkerException("worker retryable error: " + truncateForError(first.stdout(), first.stderr()));
            }
            if (first.code() == 0 && containsApiError(first.stdout(), first.stderr())) {
                appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                        "worker_api_error_detected", phase, "api_error", "worker API error detected in output", null, worker.type(), 2, 6);
                throw new IllegalStateException("worker API error: " + truncateForError(first.stdout(), first.stderr()));
            }

            if (first.code() == 0) {
                try {
                    parsedResult = primaryParser.parse(responseText);
                    appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                            "worker_result_parsed", phase, "parse", workerResultParsedMessage(parsedResult), null, worker.type(), 3, 6);
                    status = "success";
                    return parsedResult;
                } catch (RuntimeException exc) {
                    if (!allowConcludeFallback || concludePrompt == null || concludeParser == null || !driver.supportsConclude() || resolvedSession == null || resolvedSession.isBlank()) {
                        throw exc;
                    }
                    appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                            "worker_conclude_required", phase, "conclude_required", "primary parse failed, conclude fallback required", null, worker.type(), 3, 6);
                }
            } else if (!canResumeWithConcludeFallback(project.project().id(), allowConcludeFallback, concludePrompt, concludeParser, resolvedSession, first, cancellation)) {
                throw new IllegalStateException("worker failed code=" + first.code() + " stderr=" + first.stderr());
            }

            LOG.info("starting conclude fallback project={} phase={} worker={}", project.project().id(), phase, worker.name());
            appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                    "worker_conclude_started", phase, "conclude", "worker conclude fallback started", null, worker.type(), 4, 6);
            conclude = docker.exec(
                    resolvedContainer,
                    execEnv(project.project().id(), worker, workerNameOverride),
                    driver.buildConclude(worker, concludePrompt, resolvedSession),
                    null,
                    Duration.ofSeconds(concludeTimeout(taskConfig)),
                    driver::shouldStopEarly,
                    cancellationWatchdog(cancellation)
            );
            if (cancellation != null && cancellation.isCancelled()) {
                throw new java.util.concurrent.CancellationException(cancellation.reason());
            }
            appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                    "worker_conclude_response_received", phase, "conclude_response", executeResponseMessage(conclude.code(), resolvedSession), null, worker.type(), 5, 6);
            if (containsQuotaExceeded(conclude.stdout(), conclude.stderr())) {
                appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                        "worker_quota_detected", phase, "quota", "worker conclude quota limit detected", null, worker.type(), 5, 6);
                throw new QuotaExceededException("worker conclude quota exceeded: " + truncateForError(conclude.stdout(), conclude.stderr()));
            }
            if (driver.isRetryableOutput(conclude.stdout(), conclude.stderr())) {
                appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                        "worker_retryable_detected", phase, "retryable", "worker conclude returned retryable output", null, worker.type(), 5, 6);
                throw new RetryableWorkerException("worker conclude retryable error: " + truncateForError(conclude.stdout(), conclude.stderr()));
            }
            if (conclude.code() != 0) {
                throw new IllegalStateException("worker conclude failed code=" + conclude.code() + " stderr=" + conclude.stderr());
            }
            parsedResult = concludeParser.parse(driver.extractResponseText(conclude.stdout(), conclude.stderr()));
            appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                    "worker_result_parsed", phase, "conclude_parse", workerResultParsedMessage(parsedResult), null, worker.type(), 6, 6);
            status = "success";
            return parsedResult;
        } catch (RuntimeException exc) {
            error = exc.getMessage() == null ? exc.toString() : exc.getMessage();
            throw exc;
        } finally {
            appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                    "worker_execute_finished", phase, conclude == null ? "execute" : "conclude", workerResultMessage(status, error), null, worker.type(), null, null);
            if (conclude != null) {
                appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                        "worker_conclude_finished", phase, "conclude", workerResultMessage(status, error), null, worker.type(), null, null);
            }
            if (needsOrphanCleanup(first) || needsOrphanCleanup(conclude) || (cancellation != null && cancellation.isCancelled())) {
                docker.killOrphanWorkers(resolvedContainer);
            }
            if (shouldPersistTrace(parsedResult)) {
                appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                        "worker_trace_persist_started", phase, "trace", "persisting worker trace", null, worker.type(), null, null);
                Path tracePath = persistExecutionTrace(project, worker, phase, prompt, concludePrompt, command, resolvedSession, first, conclude, responseText, parsedResult, status, error, startedAt, Instant.now());
                if (tracePath != null) {
                    appendRuntimeEvent(project.project().id(), scopeType, scopeId, worker.name(),
                            "worker_trace_persisted", phase, "trace", "worker trace persisted", projectRelativeArtifact(tracePath), worker.type(), null, null);
                }
            }
        }
    }

    private Path persistExecutionTrace(
            ProjectDetail project,
            DispatchConfig.WorkerConfig worker,
            String phase,
            String prompt,
            String concludePrompt,
            DriverCommand command,
            String resolvedSession,
            ProcessOutput first,
            ProcessOutput conclude,
            String responseText,
            WorkerResult parsedResult,
            String status,
            String error,
            Instant startedAt,
            Instant finishedAt
    ) {
        Path traceDir = analysisOutputDir(project.project().id());
        if (traceDir == null) {
            return null;
        }
        try {
            Files.createDirectories(traceDir);
            Map<String, Object> trace = new LinkedHashMap<>();
            String traceId = startedAt.toEpochMilli() + "_" + UUID.randomUUID();
            trace.put("id", traceId);
            trace.put("project_id", project.project().id());
            trace.put("worker_name", worker.name());
            trace.put("worker_type", worker.type());
            trace.put("phase", phase);
            trace.put("started_at", startedAt.toString());
            trace.put("finished_at", finishedAt.toString());
            trace.put("status", status);
            trace.put("session_requested", command.session());
            trace.put("session_id", resolvedSession);
            trace.put("prompt", prompt);
            trace.put("conclude_prompt", concludePrompt);
            trace.put("argv", command.argv());
            if (first != null) {
                trace.put("exit_code", first.code());
                trace.put("stdout", first.stdout());
                trace.put("stderr", first.stderr());
            }
            if (conclude != null) {
                trace.put("conclude_exit_code", conclude.code());
                trace.put("conclude_stdout", conclude.stdout());
                trace.put("conclude_stderr", conclude.stderr());
            }
            trace.put("response_text", responseText);
            trace.put("error", error);
            if (parsedResult != null) {
                trace.put("parsed_result", mapper.convertValue(parsedResult, Map.class));
            }
            Path tracePath = traceDir.resolve(traceId + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tracePath.toFile(), trace);
            return tracePath;
        } catch (IOException ignored) {
            return null;
        }
    }

    private Path analysisOutputDir(String projectId) {
        Path root = Path.of(System.getProperty("user.dir")).resolve("output").resolve("live-analysis");
        return projectId == null || projectId.isBlank() ? root.resolve("llm-traces") : root.resolve(projectId).resolve("llm-traces");
    }

    private Path countOutputDir(String projectId) {
        Path root = Path.of(System.getProperty("user.dir")).resolve("output").resolve("live-analysis");
        return projectId == null || projectId.isBlank() ? root.resolve("_global").resolve("llm-counts") : root.resolve(projectId).resolve("llm-counts");
    }

    private String writeGraphSnapshotReference(String container, String phase, String graphYaml) {
        String path = GRAPH_SNAPSHOT_ROOT + "/" + phase + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "/graph.yaml";
        docker.writeFile(container, path, graphYaml.strip());
        return """
                The graph YAML snapshot is stored in this file inside the current container:

                %s

                Before using the graph, read the entire file and treat its contents as the YAML snapshot for this Graph section.
                """.formatted(path).trim();
    }

    private boolean shouldPersistTrace(WorkerResult result) {
        return result instanceof WorkerResult.Complete || result instanceof WorkerResult.BootstrapComplete;
    }

    private int timeout(DispatchConfig.TaskTimeout taskConfig) {
        if (taskConfig == null) {
            return 600;
        }
        return taskConfig.timeout();
    }

    private int concludeTimeout(DispatchConfig.TaskTimeout taskConfig) {
        if (taskConfig == null) {
            return 120;
        }
        return taskConfig.conclude_timeout();
    }

    private DockerCli.ExecWatchdog cancellationWatchdog(TaskCancellation cancellation) {
        if (cancellation == null) {
            return null;
        }
        return (stdout, stderr, elapsed) -> cancellation.isCancelled();
    }

    private boolean needsOrphanCleanup(ProcessOutput output) {
        return output != null && (output.timedOut() || output.stalled() || output.code() == 137 || output.code() == 124);
    }

    private String firstResolvedSession(String requestedSession, ProcessOutput output) {
        String resolved = driver.extractSession(requestedSession, output.stdout(), output.stderr());
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        return resolved;
    }

    private boolean canResumeWithConcludeFallback(String projectId, boolean allowConcludeFallback, String concludePrompt, Parser concludeParser,
                                                  String resolvedSession, ProcessOutput first, TaskCancellation cancellation) {
        if (!allowConcludeFallback || concludePrompt == null || concludeParser == null || !driver.supportsConclude()) {
            return false;
        }
        if (resolvedSession == null || resolvedSession.isBlank()) {
            return false;
        }
        if (cancellation != null && cancellation.isCancelled()) {
            return false;
        }
        if (!projectAllowsConcludeFallback(projectId)) {
            return false;
        }
        return first.code() == 124 || first.timedOut() || first.stalled() || first.code() == 137;
    }

    private boolean projectAllowsConcludeFallback(String projectId) {
        try {
            return "active".equalsIgnoreCase(runtimeClient.project(projectId).project().status());
        } catch (RuntimeException exc) {
            LOG.warn("conclude fallback status check failed project={} error={} — allowing fallback", projectId, exc.toString());
            return true;
        }
    }

    /** Check if pi output contains an API-level error (stopReason=error). */
    private boolean containsApiError(String stdout, String stderr) {
        return containsApiErrorText(stdout) || containsApiErrorText(stderr);
    }

    private boolean containsQuotaExceeded(String stdout, String stderr) {
        return containsQuotaExceededText(stdout) || containsQuotaExceededText(stderr);
    }

    /** Truncate output for inclusion in error messages. */
    private String truncateForError(String stdout, String stderr) {
        String output = preferredErrorText(stdout, stderr);
        if (output == null) return "";
        // Find the line with the error
        for (String line : output.split("\\R")) {
            if (line.contains("\"errorMessage\"") || line.toLowerCase().contains("timeout")) {
                return line.length() > 500 ? line.substring(0, 500) + "..." : line;
            }
        }
        return output.length() > 200 ? output.substring(0, 200) + "..." : output;
    }

    private boolean containsApiErrorText(String output) {
        return output != null && (output.contains("\"stopReason\":\"error\"") || output.contains("\"errorMessage\""));
    }

    private boolean containsQuotaExceededText(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String lower = output.toLowerCase();
        return lower.contains("429")
                && (output.contains("请求数限制")
                || output.contains("达到请求数限制")
                || lower.contains("rate limit")
                || lower.contains("quota exceeded"));
    }

    private String preferredErrorText(String stdout, String stderr) {
        if (stderr != null && !stderr.isBlank()) {
            return stderr;
        }
        return stdout;
    }

    private WorkerResult simulateCountOnlyExecution(
            ProjectDetail project,
            DispatchConfig.WorkerConfig worker,
            String prompt,
            String concludePrompt,
            String phase,
            Parser primaryParser,
            Parser concludeParser,
            boolean allowConcludeFallback,
            DriverCommand command,
            CountMode countMode
    ) {
        Instant startedAt = Instant.now();
        ProcessOutput first = null;
        ProcessOutput conclude = null;
        String resolvedSession = command.session();
        String responseText = "";
        WorkerResult parsedResult = null;
        String status = "error";
        String error = "";
        try {
            recordCountEvent(project.project().id(), worker, phase, "execute", prompt, countMode);
            if (countMode == CountMode.RETRYABLE) {
                throw new RetryableWorkerException("count-only retryable simulation");
            }
            if (countMode == CountMode.TIMEOUT_THEN_CONCLUDE && allowConcludeFallback && concludePrompt != null && concludeParser != null) {
                first = new ProcessOutput(124, "", "count-only execute timeout");
                recordCountEvent(project.project().id(), worker, phase, "conclude", concludePrompt, countMode);
                conclude = new ProcessOutput(0, fakeConcludePayload(phase), "");
                parsedResult = concludeParser.parse(driver.extractResponseText(conclude.stdout(), conclude.stderr()));
                status = "success";
                return parsedResult;
            }
            first = new ProcessOutput(0, fakeExecutePayload(project, phase), "");
            responseText = driver.extractResponseText(first.stdout(), first.stderr());
            parsedResult = primaryParser.parse(responseText);
            status = "success";
            return parsedResult;
        } catch (RuntimeException exc) {
            error = exc.getMessage() == null ? exc.toString() : exc.getMessage();
            throw exc;
        } finally {
            if (shouldPersistTrace(parsedResult)) {
                persistExecutionTrace(project, worker, phase, prompt, concludePrompt, command, resolvedSession, first, conclude, responseText, parsedResult, status, error, startedAt, Instant.now());
            }
        }
    }

    private void appendRuntimeEvent(String projectId, String scopeType, String scopeId, String worker, String eventType,
                                    String phase, String step, String message, String artifact, String tool,
                                    Integer progressCurrent, Integer progressTotal) {
        try {
            runtimeClient.appendRuntimeEvent(projectId, new CreateRuntimeEventRequest(
                    scopeType,
                    scopeId,
                    worker,
                    eventType,
                    phase,
                    step,
                    message,
                    artifact,
                    tool,
                    progressCurrent,
                    progressTotal
            ));
        } catch (Exception exc) {
            LOG.debug("runtime event append failed project={} eventType={} error={}", projectId, eventType, exc.toString());
        }
    }

    private String executeResponseMessage(int exitCode, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return "worker response received (code=%d, session=%s)".formatted(exitCode, sessionId);
        }
        return "worker response received (code=%d)".formatted(exitCode);
    }

    private String workerResultParsedMessage(WorkerResult result) {
        if (result == null) {
            return "worker result parsed";
        }
        if (result instanceof WorkerResult.Fact) {
            return "parsed fact result";
        }
        if (result instanceof WorkerResult.Intents intents) {
            return "parsed %d follow-up intent(s)".formatted(intents.descriptions() == null ? 0 : intents.descriptions().size());
        }
        if (result instanceof WorkerResult.Complete) {
            return "parsed completion result";
        }
        if (result instanceof WorkerResult.BootstrapComplete) {
            return "parsed bootstrap completion result";
        }
        if (result instanceof WorkerResult.Rejected rejected) {
            return rejected.reason() == null || rejected.reason().isBlank()
                    ? "worker rejected task"
                    : "worker rejected task: " + rejected.reason();
        }
        return "worker result parsed";
    }

    private String workerResultMessage(String status, String error) {
        if ("success".equals(status)) {
            return "worker execution finished";
        }
        return error == null || error.isBlank() ? "worker execution failed" : error;
    }

    private String exportProjectGraphYaml(ProjectDetail project) {
        try {
            String exported = runtimeClient.export(project.project().id(), "yaml");
            if (exported != null && !exported.isBlank()) {
                return exported.strip();
            }
        } catch (Exception exc) {
            LOG.debug("project export fallback project={} error={}", project.project().id(), exc.toString());
        }
        return prompts.exportGraphYaml(project);
    }

    private String projectRelativeArtifact(Path path) {
        Path root = Path.of(System.getProperty("user.dir")).resolve("output");
        try {
            return root.relativize(path).toString().replace('\\', '/');
        } catch (Exception ignored) {
            return path.toString();
        }
    }

    private void recordCountEvent(String projectId, DispatchConfig.WorkerConfig worker, String phase, String callType, String prompt, CountMode countMode) {
        Path dir = countOutputDir(projectId);
        try {
            Files.createDirectories(dir);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", UUID.randomUUID().toString());
            event.put("timestamp", Instant.now().toString());
            event.put("project_id", projectId);
            event.put("worker_name", worker.name());
            event.put("worker_type", worker.type());
            event.put("phase", phase);
            event.put("call_type", callType);
            event.put("count_mode", countMode.value);
            event.put("prompt_chars", prompt == null ? 0 : prompt.length());
            if (prompt != null && !prompt.isBlank()) {
                event.put("prompt_preview", prompt.substring(0, Math.min(prompt.length(), 200)));
            }
            Files.writeString(
                    dir.resolve("events.jsonl"),
                    mapper.writeValueAsString(event) + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
            );
            writeCountSummary(dir);
        } catch (IOException ignored) {
        }
    }

    private void writeCountSummary(Path dir) throws IOException {
        Path eventsPath = dir.resolve("events.jsonl");
        if (!Files.exists(eventsPath)) {
            return;
        }
        List<String> lines = Files.readAllLines(eventsPath);
        Map<String, Integer> byPhase = new LinkedHashMap<>();
        Map<String, Integer> byCallType = new LinkedHashMap<>();
        Map<String, Integer> byWorker = new LinkedHashMap<>();
        String lastTimestamp = null;
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> event = mapper.readValue(line, Map.class);
            String phase = String.valueOf(event.getOrDefault("phase", ""));
            String callType = String.valueOf(event.getOrDefault("call_type", ""));
            String worker = String.valueOf(event.getOrDefault("worker_name", ""));
            byPhase.put(phase, byPhase.getOrDefault(phase, 0) + 1);
            byCallType.put(callType, byCallType.getOrDefault(callType, 0) + 1);
            byWorker.put(worker, byWorker.getOrDefault(worker, 0) + 1);
            lastTimestamp = String.valueOf(event.getOrDefault("timestamp", lastTimestamp));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_calls", byCallType.values().stream().mapToInt(Integer::intValue).sum());
        summary.put("events", lines.stream().filter(line -> line != null && !line.isBlank()).count());
        summary.put("by_phase", byPhase);
        summary.put("by_call_type", byCallType);
        summary.put("by_worker", byWorker);
        summary.put("last_timestamp", lastTimestamp);
        mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("summary.json").toFile(), summary);
    }

    private String fakeExecutePayload(ProjectDetail project, String phase) {
        return switch (phase) {
            case "bootstrap" -> """
                    {"accepted":true,"data":{"fact":{"description":"count-only bootstrap fact"},"complete":{"description":"count-only bootstrap complete"}}}
                    """;
            case "reason" -> fakeReasonPayload(project);
            default -> """
                    {"accepted":true,"data":{"description":"count-only explore fact"}}
                    """;
        };
    }

    private String fakeConcludePayload(String phase) {
        if ("bootstrap".equals(phase)) {
            return """
                    {"accepted":true,"data":{"fact":{"description":"count-only bootstrap conclude fact"}}}
                    """;
        }
        return """
                {"accepted":true,"data":{"description":"count-only explore conclude fact"}}
                """;
    }

    private String fakeReasonPayload(ProjectDetail project) {
        long producedFacts = project.facts().stream().filter(f -> !"origin".equals(f.id()) && !"goal".equals(f.id())).count();
        if (producedFacts >= 2) {
            List<String> from = project.facts().stream()
                    .map(com.fyr.r0re.protocol.Models.Fact::id)
                    .filter(id -> !"goal".equals(id))
                    .toList();
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("accepted", true);
                Map<String, Object> data = new LinkedHashMap<>();
                Map<String, Object> complete = new LinkedHashMap<>();
                complete.put("description", "count-only complete after " + producedFacts + " findings");
                complete.put("from", from);
                data.put("complete", complete);
                payload.put("data", data);
                return mapper.writeValueAsString(payload);
            } catch (IOException ignored) {
                return "{\"accepted\":true,\"data\":{\"intents\":[]}}";
            }
        }
        return """
                {"accepted":true,"data":{"intents":[{"description":"count-only explore intent"}]}}
                """;
    }

    private CountMode countMode(DispatchConfig.WorkerConfig worker) {
        Map<String, String> env = worker.env() == null ? Map.of() : worker.env();
        return CountMode.from(env.get(COUNT_MODE_ENV));
    }

    private Map<String, String> execEnv(String projectId, DispatchConfig.WorkerConfig worker) {
        return execEnv(projectId, worker, null);
    }

    private Map<String, String> execEnv(String projectId, DispatchConfig.WorkerConfig worker, String workerNameOverride) {
        Map<String, String> env = new LinkedHashMap<>(worker.env() == null ? Map.of() : worker.env());
        env.put("R0RE_BLACKBOARD_URL", containerReachableServerUrl());
        env.put("R0RE_PROJECT_ID", projectId);
        env.put("R0RE_WORKER_NAME", workerNameOverride == null || workerNameOverride.isBlank() ? worker.name() : workerNameOverride);
        return env;
    }

    /**
     * Workers run inside a container; even with network_mode=host, on Docker Desktop (macOS)
     * the container's loopback is the VM's, not the host's. Rewrite loopback server URLs to
     * host.docker.internal (already mapped via extra_hosts) so the blackboard is reachable.
     */
    private String containerReachableServerUrl() {
        if (server == null) {
            return "http://host.docker.internal:8000";
        }
        return server
                .replace("://127.0.0.1", "://host.docker.internal")
                .replace("://localhost", "://host.docker.internal");
    }

    private enum CountMode {
        OFF("off"),
        SUCCESS("success"),
        TIMEOUT_THEN_CONCLUDE("timeout_then_conclude"),
        RETRYABLE("retryable");

        private final String value;

        CountMode(String value) {
            this.value = value;
        }

        private boolean enabled() {
            return this != OFF;
        }

        private static CountMode from(String raw) {
            if (raw == null || raw.isBlank()) {
                return OFF;
            }
            String normalized = raw.trim().toLowerCase();
            for (CountMode mode : values()) {
                if (mode.value.equals(normalized)) {
                    return mode;
                }
            }
            return OFF;
        }
    }

    @FunctionalInterface
    private interface Parser {
        WorkerResult parse(String text);
    }
}
