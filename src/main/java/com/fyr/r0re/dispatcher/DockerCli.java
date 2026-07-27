package com.fyr.r0re.dispatcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docker client — exec uses Docker Java SDK (mirrors Cairn's ManagedProcess),
 * all other operations use CLI fallback for simplicity.
 */
public class DockerCli implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DockerCli.class);
    private static final String PREFIX = "r0re-dispatch-";
    private static final long KILL_JOIN_TIMEOUT_MS = 5000;
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final String EXEC_UID = "0";

    private final DispatchConfig.ContainerConfig config;
    private final DockerClient docker;
    private final DockerHttpClient httpClient;

    @FunctionalInterface
    public interface StreamStopCondition {
        boolean shouldStop(String stdout, String stderr);
    }

    @FunctionalInterface
    public interface ExecWatchdog {
        boolean shouldStop(String stdout, String stderr, Duration elapsed);
    }

    public DockerCli(DispatchConfig.ContainerConfig config) {
        this(config, true);
    }

    DockerCli(DispatchConfig.ContainerConfig config, boolean initializeClient) {
        this.config = config;
        if (!initializeClient) {
            this.docker = null;
            this.httpClient = null;
            return;
        }
        var dcc = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        this.httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(dcc.getDockerHost())
                .sslConfig(dcc.getSSLConfig())
                .build();
        this.docker = DockerClientImpl.getInstance(dcc, this.httpClient);
    }

    @Override
    public void close() {
        if (docker != null) {
            try {
                docker.close();
            } catch (IOException e) {
                LOG.debug("docker client close failed: {}", e.toString());
            }
        }
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (IOException e) {
                LOG.debug("docker http client close failed: {}", e.toString());
            }
        }
    }

    // ── Container lifecycle (CLI-based, same as before) ────────────────

    public synchronized String ensureRunning(String projectId) {
        String name = containerName(projectId);
        // Inspect via SDK
        try {
            var inspect = docker.inspectContainerCmd(name).exec();
            if (Boolean.TRUE.equals(inspect.getState().getRunning())) {
                return name;
            }
            docker.startContainerCmd(name).exec();
            return name;
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            // Need to create
        } catch (Exception e) {
            LOG.warn("inspect/start container {} failed: {}", name, e.getMessage());
        }

        // Create via CLI (simpler than SDK for volume/cap_add options)
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("-d");
        command.add("--name");
        command.add(name);
        command.add("--user");
        command.add(EXEC_UID);
        if (config != null && config.network_mode() != null && !config.network_mode().isBlank()) {
            command.add("--network");
            command.add(config.network_mode());
        }
        if (config != null && config.volumes() != null) {
            for (var entry : config.volumes().entrySet()) {
                DispatchConfig.Volume volume = entry.getValue();
                if (volume.bind() == null || volume.bind().isBlank()) continue;
                String hostPath = resolveHostPath(entry.getKey(), projectId);
                String mode = volume.mode() == null || volume.mode().isBlank() ? "rw" : volume.mode();
                ensureHostDirectory(hostPath, mode);
                command.add("-v");
                command.add(hostPath + ":" + volume.bind() + ":" + mode);
            }
        }
        if (config != null) {
            for (String cap : config.capAdd()) {
                command.add("--cap-add");
                command.add(cap);
            }
        }
        // extra_hosts: --add-host
        if (config != null) {
            for (String host : config.extraHosts()) {
                command.add("--add-host");
                command.add(host);
            }
        }
        // container_env: -e
        if (config != null) {
            for (var entry : config.containerEnv().entrySet()) {
                command.add("-e");
                command.add(entry.getKey() + "=" + resolveEnvPlaceholders(entry.getValue()));
            }
        }
        String image = (config != null && config.image() != null && !config.image().isBlank())
                ? config.image() : "r0re-android-worker:latest";
        command.add(image);
        command.add("sleep");
        command.add("infinity");
        ProcessOutput created = runCli(Duration.ofSeconds(60), command, null);
        if (created.code() != 0) {
            // Check if name conflict
            String state = inspectState(name);
            if (state != null) {
                if (!"running".equals(state)) {
                    try {
                        docker.removeContainerCmd(name).withForce(true).exec();
                    } catch (Exception ignored) {
                        // If the container is already marked for removal, a forced delete is the fastest recovery path.
                    }
                    waitForContainerGone(name, Duration.ofSeconds(5));
                    created = runCli(Duration.ofSeconds(60), command, null);
                    if (created.code() == 0) {
                        return name;
                    }
                    try {
                        docker.startContainerCmd(name).exec();
                    } catch (Exception ignored) {
                        // Fall through and let the caller retry on the next dispatcher tick.
                    }
                }
                return name;
            }
            throw new IllegalStateException("failed to create container " + name + ": " + created.stderr());
        }
        return name;
    }

    // ── Exec — Docker Java SDK (mirrors Cairn's ManagedProcess) ─────────

    /**
     * Execute command inside container via Docker SDK streaming exec.
     * Returns when output stream closes (even if process hangs) — this is the key fix.
     */
    public ProcessOutput exec(String container, Map<String, String> env, List<String> command, String stdin, Duration timeout) {
        return exec(container, env, command, stdin, timeout, null);
    }

    public ProcessOutput exec(String container, Map<String, String> env, List<String> command, String stdin, Duration timeout, StreamStopCondition stopCondition) {
        return exec(container, env, command, stdin, timeout, stopCondition, null);
    }

    public ProcessOutput exec(String container, Map<String, String> env, List<String> command, String stdin, Duration timeout,
                              StreamStopCondition stopCondition, ExecWatchdog watchdog) {
        boolean unlimitedTimeout = timeout == null || timeout.isZero() || timeout.isNegative();
        Duration effectiveTimeout = unlimitedTimeout ? Duration.ZERO : timeout;
        String timeoutLabel = unlimitedTimeout ? "unlimited" : effectiveTimeout.toSeconds() + "s";
        String[] activeExecId = {null};

        try {
            // Build env list
            List<String> envList = new ArrayList<>();
            if (env != null) {
                for (var entry : env.entrySet()) {
                    envList.add(entry.getKey() + "=" + entry.getValue());
                }
            }

            // Create exec (like Cairn's exec_create)
            ExecCreateCmdResponse execCreate = docker.execCreateCmd(container)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withAttachStdin(stdin != null)
                    .withTty(false)
                    .withUser(EXEC_UID)
                    .withEnv(envList)
                    .withCmd(command.toArray(new String[0]))
                    .exec();

            String execId = execCreate.getId();
            activeExecId[0] = execId;

            // Read stream in background thread (like Cairn's _read_stream)
            ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
            CountDownLatch streamDone = new CountDownLatch(1);
            int[] exitCode = {1};
            String[] readError = {null};
            boolean[] stoppedEarly = {false};
            String[] stopReason = {null};
            Object outputLock = new Object();

            Thread reader = new Thread(() -> {
                com.github.dockerjava.api.async.ResultCallback.Adapter<Frame> callback = null;
                try {
                    callback = new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            if (frame == null) return;
                            byte[] payload = frame.getPayload();
                            int size = payload.length;
                            synchronized (outputLock) {
                                if (frame.getStreamType() == StreamType.STDOUT || frame.getStreamType() == StreamType.RAW) {
                                    stdoutBuf.write(payload, 0, size);
                                } else if (frame.getStreamType() == StreamType.STDERR) {
                                    stderrBuf.write(payload, 0, size);
                                }
                            }
                            if (stopCondition != null && stopCondition.shouldStop(readOutput(stdoutBuf, outputLock), readOutput(stderrBuf, outputLock))) {
                                stoppedEarly[0] = true;
                                stopReason[0] = "stream_stop";
                                killExec(execId, container);
                            }
                        }
                    };

                    if (stdin != null) {
                        docker.execStartCmd(execId)
                                .withStdIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)))
                                .withTty(false)
                                .exec(callback)
                                .awaitCompletion();
                    } else {
                        docker.execStartCmd(execId)
                                .withTty(false)
                                .exec(callback)
                                .awaitCompletion();
                    }
                } catch (Exception e) {
                    readError[0] = e.getMessage();
                } finally {
                    if (callback != null) {
                        try {
                            callback.close();
                        } catch (IOException ignored) {
                        }
                    }
                    // Resolve exit code (like Cairn's _resolve_exit_code)
                    exitCode[0] = resolveExitCode(execId);
                    streamDone.countDown();
                }
            }, "docker-exec-reader");
            reader.setDaemon(true);
            reader.start();

            long startNanos = System.nanoTime();
            long deadline = unlimitedTimeout ? Long.MAX_VALUE : System.currentTimeMillis() + effectiveTimeout.toMillis() + 15_000;
            boolean finished;
            while (!(finished = streamDone.await(1, TimeUnit.SECONDS))) {
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                if (watchdog != null && watchdog.shouldStop(
                        readOutput(stdoutBuf, outputLock),
                        readOutput(stderrBuf, outputLock),
                        Duration.ofMillis(elapsedMs))) {
                    LOG.warn("exec watchdog requested stop container={} execId={}", container, execId);
                    stopReason[0] = "stalled";
                    killExec(execId, container);
                    streamDone.await(KILL_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    finished = true;
                    exitCode[0] = 137;
                    break;
                }
                if (!unlimitedTimeout && System.currentTimeMillis() >= deadline) {
                    break;
                }
            }

            if (!finished) {
                LOG.warn("exec timed out after {} container={} execId={}", timeoutLabel, container, execId);
                stopReason[0] = "timeout";
                killExec(execId, container);
                streamDone.await(KILL_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (streamDone.getCount() > 0) {
                    reader.interrupt();
                }
                killOrphanWorkers(container);
                exitCode[0] = 137;
            } else if (stoppedEarly[0] && exitCode[0] == 0) {
                exitCode[0] = 137;
            }

            String stdoutStr = readOutput(stdoutBuf, outputLock);
            String stderrStr = readOutput(stderrBuf, outputLock);
            if (readError[0] != null && stderrStr.isBlank()) {
                stderrStr = readError[0];
            }
            return new ProcessOutput(exitCode[0], stdoutStr, stderrStr, stopReason[0]);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (activeExecId[0] != null) {
                killExec(activeExecId[0], container);
                killOrphanWorkers(container);
            }
            return new ProcessOutput(130, "", e.toString(), "cancelled");
        } catch (Exception e) {
            LOG.error("exec failed container={}: {}", container, e.getMessage());
            return new ProcessOutput(127, "", e.toString());
        }
    }

    private String readOutput(ByteArrayOutputStream buffer, Object lock) {
        synchronized (lock) {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /** Resolve exit code via exec_inspect — mirrors Cairn's _resolve_exit_code. */
    private int resolveExitCode(String execId) {
        long deadline = System.currentTimeMillis() + KILL_JOIN_TIMEOUT_MS;
        while (true) {
            try {
                InspectExecResponse inspect = docker.inspectExecCmd(execId).exec();
                Long code = inspect.getExitCodeLong();
                if (code != null) return code.intValue();
                if (inspect.isRunning() == null || !inspect.isRunning()) return 1;
            } catch (Exception e) {
                return 137;
            }
            if (System.currentTimeMillis() >= deadline) return 137;
            try { Thread.sleep(100); } catch (InterruptedException e) { return 137; }
        }
    }

    /** Kill exec by PID — mirrors Cairn's ManagedProcess._kill_pid. */
    private void killExec(String execId, String container) {
        try {
            InspectExecResponse details = docker.inspectExecCmd(execId).exec();
            if (!Boolean.TRUE.equals(details.isRunning())) return;
            Integer pid = details.getPid();
            if (pid == null || pid == 0) {
                LOG.warn("exec missing pid for kill execId={}", execId);
                return;
            }
            // Run kill -KILL inside container
            try {
                var killExec = docker.execCreateCmd(container)
                        .withAttachStdout(false).withAttachStderr(false)
                        .withCmd("/bin/sh", "-lc", "kill -KILL " + pid + " 2>/dev/null; pkill -9 -P " + pid + " 2>/dev/null; true")
                        .exec();
                var callback = new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>();
                try {
                    docker.execStartCmd(killExec.getId()).exec(callback).awaitCompletion(5, TimeUnit.SECONDS);
                } finally {
                    callback.close();
                }
                LOG.info("killed exec pid={} container={}", pid, container);
            } catch (Exception e) {
                LOG.warn("kill pid {} failed: {}", pid, e.getMessage());
            }
        } catch (Exception e) {
            LOG.warn("failed to kill exec execId={}: {}", execId, e.getMessage());
        }
    }

    public void killOrphanWorkers(String container) {
        try {
            var execCreate = docker.execCreateCmd(container)
                    .withAttachStdout(false).withAttachStderr(false)
                    .withCmd("/bin/sh", "-lc", "pkill -9 -f '(^|/)pi($| )' 2>/dev/null; pkill -9 -f 'bin/pi' 2>/dev/null; pkill -9 -f 'codex' 2>/dev/null; pkill -9 -f 'claude' 2>/dev/null; pkill -9 -f '(^|/)kimi($| )' 2>/dev/null; true")
                    .exec();
            var callback = new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>();
            try {
                docker.execStartCmd(execCreate.getId()).exec(callback).awaitCompletion(10, TimeUnit.SECONDS);
            } finally {
                callback.close();
            }
        } catch (Exception ignored) {}
    }

    // ── Ephemeral container (CLI) ───────────────────────────────────────

    public ProcessOutput runEphemeral(Map<String, String> env, List<String> command, String stdin, Duration timeout) {
        List<String> argv = new ArrayList<>();
        argv.add("docker"); argv.add("run"); argv.add("--rm");
        if (stdin != null) argv.add("-i");
        if (config != null && config.network_mode() != null && !config.network_mode().isBlank()) {
            argv.add("--network"); argv.add(config.network_mode());
        }
        if (config != null && config.volumes() != null) {
            for (var entry : config.volumes().entrySet()) {
                DispatchConfig.Volume v = entry.getValue();
                if (v.bind() != null && !v.bind().isBlank()) {
                    String mode = v.mode() == null || v.mode().isBlank() ? "rw" : v.mode();
                    argv.add("-v"); argv.add(resolveHostPath(entry.getKey(), null) + ":" + v.bind() + ":" + mode);
                }
            }
        }
        if (env != null) {
            for (var entry : env.entrySet()) { argv.add("-e"); argv.add(entry.getKey() + "=" + entry.getValue()); }
        }
        if (config != null) {
            for (String cap : config.capAdd()) { argv.add("--cap-add"); argv.add(cap); }
        }
        argv.add((config != null && config.image() != null && !config.image().isBlank()) ? config.image() : "r0re-android-worker:latest");
        argv.addAll(command);
        return runCli(timeout, argv, stdin);
    }

    // ── Utility ─────────────────────────────────────────────────────────

    public void writeFile(String container, String path, String content) {
        Path tempFile = null;
        try {
            Path targetPath = Path.of(path);
            Path parent = targetPath.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("path must include parent directory: " + path);
            }
            ProcessOutput mkdir = exec(container, null, List.of("/bin/sh", "-lc", "mkdir -p " + shellQuote(parent.toString())), null, Duration.ofSeconds(10));
            if (mkdir.code() != 0) {
                throw new IllegalStateException("failed to prepare directory " + parent + ": " + mkdir.stderr());
            }
            tempFile = Files.createTempFile("r0re-write-", ".tmp");
            Files.writeString(tempFile, content);
            List<String> command = List.of("docker", "cp", tempFile.toString(), container + ":" + path);
            ProcessOutput copied = runCli(Duration.ofMinutes(2), command, null);
            if (copied.code() != 0) {
                throw new IllegalStateException("failed to write " + path + ": " + copied.stderr());
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to stage " + path + " on host", e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public String containerName(String projectId) { return PREFIX + projectId.replace("/", "-"); }

    public boolean cleanupCompleted(String projectId) {
        String name = containerName(projectId);
        String state = inspectState(name);
        if (state == null) return true;
        String action = (config == null || config.completed_action() == null) ? "stop" : config.completed_action();
        try {
            if ("remove".equals(action)) {
                docker.removeContainerCmd(name).withForce(true).exec();
                return inspectState(name) == null;
            }
            if ("running".equals(state)) {
                docker.stopContainerCmd(name).withTimeout(1).exec();
                return !"running".equals(inspectState(name));
            }
        } catch (Exception e) { LOG.warn("cleanup failed container={}: {}", name, e.getMessage()); }
        return true;
    }

    public boolean cleanupStopped(String projectId) {
        String name = containerName(projectId);
        if (!"running".equals(inspectState(name))) return true;
        try { docker.stopContainerCmd(name).withTimeout(1).exec(); } catch (Exception e) { LOG.warn("stop failed: {}", e.getMessage()); }
        if (!"running".equals(inspectState(name))) return true;
        try {
            docker.removeContainerCmd(name).withForce(true).exec();
        } catch (Exception e) {
            LOG.warn("force remove stopped container failed: {}", e.getMessage());
        }
        return inspectState(name) == null;
    }

    public boolean cleanupOrphan(String containerName) {
        if (inspectState(containerName) == null) return true;
        try { docker.removeContainerCmd(containerName).withForce(true).exec(); } catch (Exception e) { LOG.warn("remove orphan failed: {}", e.getMessage()); }
        return inspectState(containerName) == null;
    }

    public Set<String> managedContainerNames() {
        Set<String> names = new LinkedHashSet<>();
        try {
            for (var c : docker.listContainersCmd().withShowAll(true).exec()) {
                for (String n : c.getNames()) {
                    String clean = n.startsWith("/") ? n.substring(1) : n;
                    if (clean.startsWith(PREFIX)) names.add(clean);
                }
            }
        } catch (Exception e) { LOG.warn("list containers failed: {}", e.getMessage()); }
        return names;
    }

    public String inspectState(String name) {
        try {
            return docker.inspectContainerCmd(name).exec().getState().getStatus();
        } catch (com.github.dockerjava.api.exception.NotFoundException e) { return null; }
        catch (Exception e) { return null; }
    }

    public void copyDirectoryFromContainer(String container, String containerPath, Path hostTargetDir) {
        try {
            if (hostTargetDir == null) {
                throw new IllegalArgumentException("hostTargetDir must not be null");
            }
            deleteDirectoryContents(hostTargetDir);
            Files.createDirectories(hostTargetDir);
            List<String> command = List.of(
                    "docker", "cp",
                    container + ":" + containerPath + "/.",
                    hostTargetDir.toString()
            );
            ProcessOutput copied = runCli(Duration.ofMinutes(5), command, null);
            if (copied.code() != 0) {
                throw new IllegalStateException("docker cp failed: " + copied.stderr());
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to prepare host target dir " + hostTargetDir, e);
        }
    }

    /**
     * Copy a single host file into a container directory.
     * Creates the target directory inside the container if it does not exist.
     * Silently skips if the host file does not exist.
     */
    public void copyFileToContainer(String container, Path hostPath, String containerDir) {
        if (!Files.exists(hostPath)) {
            LOG.warn("host file does not exist, skipping: {}", hostPath);
            return;
        }
        // Ensure container directory exists
        ProcessOutput mkdir = exec(container, null, List.of("/bin/sh", "-lc", "mkdir -p " + shellQuote(containerDir)), null, Duration.ofSeconds(10));
        if (mkdir.code() != 0) {
            throw new IllegalStateException("mkdir in container failed: " + mkdir.stderr());
        }
        List<String> command = List.of(
                "docker", "cp",
                hostPath.toString(),
                container + ":" + containerDir + "/"
        );
        ProcessOutput copied = runCli(Duration.ofMinutes(2), command, null);
        if (copied.code() != 0) {
            throw new IllegalStateException("docker cp to container failed: " + copied.stderr());
        }
        LOG.info("copied host file into container: {} → {}:{}", hostPath, container, containerDir);
    }

    // ── CLI fallback ────────────────────────────────────────────────────

    private ProcessOutput runCli(Duration timeout, List<String> command, String stdin) {
        try {
            Process process = new ProcessBuilder(command).start();
            if (stdin != null) {
                process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().close();
            } else {
                try {
                    process.getOutputStream().close();
                } catch (IOException ignored) {
                    // Some short-lived commands exit before stdin is closed.
                }
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) { process.destroyForcibly(); return new ProcessOutput(124, readStream(process.getInputStream()), "timed out"); }
                if (process.waitFor(Math.min(remainingMs, 200), TimeUnit.MILLISECONDS)) {
                    return new ProcessOutput(process.exitValue(), readStream(process.getInputStream()), readStream(process.getErrorStream()));
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return new ProcessOutput(130, "", e.toString()); }
        catch (IOException e) { return new ProcessOutput(127, "", e.toString()); }
    }

    private String readStream(java.io.InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Stream closed")) {
                return "";
            }
            throw e;
        }
    }
    private String shellQuote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }

    private String resolveHostPath(String rawPath, String projectId) {
        if (rawPath == null || rawPath.isBlank()) return rawPath;
        return resolveEnvPlaceholders(rawPath).replace("{project_id}", projectId == null ? "" : projectId);
    }

    String resolveHostPathForTest(String rawPath, String projectId) {
        return resolveHostPath(rawPath, projectId);
    }

    private void ensureHostDirectory(String hostPath, String mode) {
        if (hostPath == null || hostPath.isBlank() || "ro".equalsIgnoreCase(mode)) {
            return;
        }
        try {
            Files.createDirectories(Path.of(hostPath));
        } catch (IOException e) {
            throw new IllegalStateException("failed to create host directory " + hostPath, e);
        }
    }

    private void deleteDirectoryContents(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(directory))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private void waitForContainerGone(String name, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (inspectState(name) == null) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String resolveEnvPlaceholders(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = System.getenv(matcher.group(1));
            if (replacement == null) {
                replacement = matcher.group(0);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
