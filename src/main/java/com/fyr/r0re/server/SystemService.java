package com.fyr.r0re.server;

import com.fyr.r0re.config.R0reProperties;
import com.fyr.r0re.protocol.Models.AutomationJob;
import com.fyr.r0re.protocol.Models.DependencyStatus;
import com.fyr.r0re.protocol.Models.LockStatus;
import com.fyr.r0re.protocol.Models.ProcessStatus;
import com.fyr.r0re.protocol.Models.SystemStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SystemService {
    private static final String RUNTIME_SCHEMA_VERSION = "harness-loop.v1";
    private final R0reService service;
    private final R0reProperties properties;
    private final String dataDir;
    private final int serverPort;
    private final Path rootDir;

    public SystemService(
            R0reService service,
            R0reProperties properties,
            @Value("${r0re.storage.data-dir}") String dataDir,
            @Value("${server.port}") int serverPort
    ) {
        this.service = service;
        this.properties = properties;
        this.dataDir = dataDir;
        this.serverPort = serverPort;
        this.rootDir = Path.of(System.getProperty("user.dir"));
    }

    public SystemStatus systemStatus() {
        List<AutomationJob> jobs = service.automationJobs();
        return new SystemStatus(
                "http://127.0.0.1:" + serverPort,
                dataDir,
                properties.getDispatcher().getConfig(),
                runtimeSchema(),
                RUNTIME_SCHEMA_VERSION,
                serveStatus(),
                dispatchStatuses(),
                jobs,
                dependencyStatuses(),
                lockStatuses()
        );
    }

    private ProcessStatus serveStatus() {
        Path pidFile = rootDir.resolve("output/r0re-serve.pid");
        Path lockFile = rootDir.resolve("output/locks/r0re-serve-" + serverPort + ".lock");
        return processStatus("serve", pidFile, lockFile, null, null, rootDir.resolve("output/r0re-serve.log"));
    }

    private List<ProcessStatus> dispatchStatuses() {
        Path lockDir = rootDir.resolve("output/locks");
        List<ProcessStatus> statuses = new ArrayList<>();
        Path pidFile = rootDir.resolve("output/r0re-dispatch.pid");
        Path logFile = rootDir.resolve("output/r0re-dispatch.log");
        if (Files.isDirectory(lockDir)) {
            try (Stream<Path> stream = Files.list(lockDir)) {
                stream.filter(path -> path.getFileName().toString().startsWith("r0re-dispatch-"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(lock -> statuses.add(processStatus("dispatch", pidFile, lock, lockValue(lock, "profile"), lockValue(lock, "config"), logFile)));
            } catch (IOException ignored) {
            }
        }
        if (statuses.isEmpty()) {
            statuses.add(processStatus("dispatch", pidFile, null, null, properties.getDispatcher().getConfig(), logFile));
        }
        return statuses;
    }

    private ProcessStatus processStatus(String name, Path pidFile, Path lockFile, String profile, String config, Path logFile) {
        String pid = readPid(lockFile != null && Files.exists(lockFile) ? lockFile : pidFile);
        boolean running = pid != null && isRunning(pid);
        return new ProcessStatus(
                name,
                Files.exists(pidFile) ? pidFile.toString() : null,
                lockFile != null && Files.exists(lockFile) ? lockFile.toString() : null,
                pid,
                running,
                profile,
                config,
                Files.exists(logFile) ? logFile.toString() : null
        );
    }

    private String runtimeSchema() {
        Path dbPath = databasePath();
        if (!Files.exists(dbPath)) {
            return "unknown";
        }
        boolean hasCandidates = hasTable(dbPath, "completion_candidates");
        boolean hasHealth = hasTable(dbPath, "project_health");
        boolean hasAutomation = hasTable(dbPath, "automation_jobs");
        if (hasCandidates && hasHealth && hasAutomation) {
            return "completion-health-automation";
        }
        if (hasCandidates && hasHealth) {
            return "completion-health";
        }
        if (hasCandidates) {
            return "completion-candidates";
        }
        return "base";
    }

    private List<DependencyStatus> dependencyStatuses() {
        List<DependencyStatus> statuses = new ArrayList<>();
        Path dbPath = databasePath();
        statuses.add(new DependencyStatus(
                "database",
                Files.exists(dbPath),
                Files.exists(dbPath) ? "ready" : "missing",
                dbPath.toAbsolutePath().toString()
        ));
        statuses.add(commandDependency(
                "docker",
                List.of("docker", "info", "--format", "{{.ServerVersion}}"),
                Duration.ofSeconds(5),
                "unavailable",
                "docker daemon not reachable"
        ));
        statuses.add(new DependencyStatus(
                "runtime-schema",
                true,
                runtimeSchema(),
                "version=" + RUNTIME_SCHEMA_VERSION
        ));
        return statuses;
    }

    private DependencyStatus commandDependency(
            String name,
            List<String> command,
            Duration timeout,
            String failureStatus,
            String failureDetail
    ) {
        if (!commandAvailable(command.get(0))) {
            return new DependencyStatus(name, false, "missing", command.get(0) + " not found");
        }
        try {
            Process process = new ProcessBuilder(command)
                    .directory(rootDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new DependencyStatus(name, false, "timeout", "command timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() == 0) {
                return new DependencyStatus(name, true, "ready", output.isBlank() ? "ok" : output);
            }
            return new DependencyStatus(name, false, failureStatus, output.isBlank() ? failureDetail : output);
        } catch (Exception e) {
            return new DependencyStatus(name, false, failureStatus, failureDetail + ": " + e.getMessage());
        }
    }

    private boolean commandAvailable(String command) {
        try {
            Process process = new ProcessBuilder("sh", "-lc", "command -v " + command)
                    .directory(rootDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private List<LockStatus> lockStatuses() {
        Path lockDir = rootDir.resolve("output/locks");
        List<LockStatus> statuses = new ArrayList<>();
        if (!Files.isDirectory(lockDir)) {
            return statuses;
        }
        try (Stream<Path> stream = Files.list(lockDir)) {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(lock -> {
                        String pid = readPid(lock);
                        boolean running = pid != null && isRunning(pid);
                        statuses.add(new LockStatus(
                                lock.toString(),
                                lockKind(lock),
                                pid,
                                running,
                                pid != null && !running,
                                lockValue(lock, "profile"),
                                lockValue(lock, "config")
                        ));
                    });
        } catch (IOException ignored) {
        }
        return statuses;
    }

    private String lockKind(Path lock) {
        String name = lock.getFileName().toString();
        if (name.startsWith("r0re-serve-")) {
            return "serve";
        }
        if (name.startsWith("r0re-dispatch-")) {
            return "dispatch";
        }
        return "unknown";
    }

    private Path databasePath() {
        Path resolved = Path.of(dataDir);
        if (!resolved.isAbsolute()) {
            resolved = rootDir.resolve(resolved);
        }
        return resolved.resolve("r0re.db");
    }

    private boolean hasTable(Path dbPath, String tableName) {
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private String readPid(Path path) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(path)) {
                if (line.startsWith("pid=")) {
                    return line.substring(4).trim();
                }
            }
            String value = Files.readString(path, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value.lines().findFirst().orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private String lockValue(Path lockFile, String key) {
        if (lockFile == null || !Files.exists(lockFile)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(lockFile)) {
                String prefix = key + "=";
                if (line.startsWith(prefix)) {
                    return line.substring(prefix.length()).trim();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private boolean isRunning(String pid) {
        try {
            long value = Long.parseLong(pid);
            return ProcessHandle.of(value).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception ignored) {
            return false;
        }
    }
}
