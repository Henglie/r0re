package com.fyr.r0re.dispatcher;

import com.fyr.r0re.protocol.Models.Fact;
import com.fyr.r0re.protocol.Models.Hint;
import com.fyr.r0re.protocol.Models.Intent;
import com.fyr.r0re.protocol.Models.ProjectDetail;
import com.fyr.r0re.protocol.Models.ProjectMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalCliWorkerAdapterTest {
    @Test
    void bootstrapFallsBackToConcludeAfterTimeout() {
        FakeDocker docker = new FakeDocker(
                new ProcessOutput(124, "", "timed out"),
                new ProcessOutput(0, "{\"accepted\":true,\"data\":{\"fact\":{\"description\":\"fact from conclude\"}}}", "")
        );
        ExternalCliWorkerAdapter adapter = new ExternalCliWorkerAdapter(null, docker, new PromptFactory("mock"), new FakeDriver(), null, new NoopDispatcherClient(project()));

        WorkerResult result = adapter.bootstrap(project(), worker(), new DispatchConfig.TaskTimeout(1, 1), new TaskCancellation());

        assertInstanceOf(WorkerResult.Fact.class, result);
        assertEquals("fact from conclude", ((WorkerResult.Fact) result).description());
        assertEquals(List.of("execute", "conclude"), docker.phases);
    }

    @Test
    void bootstrapDoesNotRecoverFromStalledPiSessionFileLikeCairn(@TempDir Path tempDir) throws Exception {
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            Path sessionFile = tempDir.resolve("output/pi-sessions/proj_001/pi/sessions/run.jsonl");
            Files.createDirectories(sessionFile.getParent());
            Files.writeString(sessionFile, """
                    {"type":"session","id":"sess-recover"}
                    {"type":"message","message":{"role":"assistant","content":[{"type":"text","text":"partial"}]}}
                    """);

            FakeDocker docker = new FakeDocker(
                    new ProcessOutput(137, "", "", "stalled"),
                    new ProcessOutput(0, "{\"accepted\":true,\"data\":{\"fact\":{\"description\":\"fact from conclude\"}}}", "")
            );
            ExternalCliWorkerAdapter adapter = new ExternalCliWorkerAdapter(null, docker, new PromptFactory("mock"), new PiWorkerDriver(), null, new NoopDispatcherClient(project()));

            assertThrows(IllegalStateException.class,
                    () -> adapter.bootstrap(project(), piWorker(), new DispatchConfig.TaskTimeout(60, 60), new TaskCancellation()));
            assertEquals(1, docker.phases.size());
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void piDriverExtractsSessionId() {
        PiWorkerDriver driver = new PiWorkerDriver();
        String session = driver.extractSession(null, "{\"type\":\"session\",\"id\":\"sess-123\"}", "");
        assertEquals("sess-123", session);
    }

    @Test
    void dockerCliResolvesProjectIdInHostPath() {
        DockerCli docker = new DockerCli(null);
        String home = System.getenv("HOME");
        if (home == null || home.isBlank()) {
            assertEquals("/tmp/out/proj_123", docker.resolveHostPathForTest("/tmp/out/{project_id}", "proj_123"));
            return;
        }
        assertEquals(home + "/out/proj_123", docker.resolveHostPathForTest("${HOME}/out/{project_id}", "proj_123"));
    }

    @Test
    void bootstrapDoesNotTreatPiTimeoutTextAsRetryable() {
        FakeDocker docker = new FakeDocker(
                new ProcessOutput(137, """
                        {"type":"message","message":{"role":"toolResult","content":[{"type":"text","text":"provider timeout"}]}}
                        {"errorMessage":"provider timed out"}
                        """, "")
        );
        ExternalCliWorkerAdapter adapter = new ExternalCliWorkerAdapter(null, docker, new PromptFactory("mock"), new PiWorkerDriver(), null, new NoopDispatcherClient(project()));

        assertThrows(IllegalStateException.class,
                () -> adapter.bootstrap(project(), piWorker(), new DispatchConfig.TaskTimeout(900, 120), new TaskCancellation()));
    }

    @Test
    void bootstrapStopsOnQuotaExceededText() {
        FakeDocker docker = new FakeDocker(
                new ProcessOutput(0, """
                        {"type":"message","message":{"role":"assistant"},"stopReason":"error","errorMessage":"429 您已达到请求数限制：300分钟内最多请求800次"}
                        """, "")
        );
        ExternalCliWorkerAdapter adapter = new ExternalCliWorkerAdapter(null, docker, new PromptFactory("mock"), new PiWorkerDriver(), null, new NoopDispatcherClient(project()));

        assertThrows(ExternalCliWorkerAdapter.QuotaExceededException.class,
                () -> adapter.bootstrap(project(), piWorker(), new DispatchConfig.TaskTimeout(900, 120), new TaskCancellation()));
    }

    @Test
    void taskHealthcheckFailsAsRetryableWorkerException() {
        FakeDocker docker = new FakeDocker(
                new ProcessOutput(1, "", "healthcheck failed")
        );
        ExternalCliWorkerAdapter adapter = new ExternalCliWorkerAdapter(null, docker, new PromptFactory("mock"), new FakeDriver());

        assertThrows(ExternalCliWorkerAdapter.RetryableWorkerException.class,
                () -> adapter.taskHealthcheck("proj_001", worker(), 1));
    }

    @Test
    void piDriverDoesNotStopEarlyOnTimeoutText() {
        PiWorkerDriver driver = new PiWorkerDriver();
        assertFalse(driver.shouldStopEarly("{\"errorMessage\":\"provider timed out\"}", ""));
    }

    @Test
    void countOnlyModeWritesCountFilesWithoutDocker(@TempDir Path tempDir) throws Exception {
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            ExternalCliWorkerAdapter adapter = new ExternalCliWorkerAdapter(null, new NoDockerFakeDocker(), new PromptFactory("mock"), new PiWorkerDriver(), null, new NoopDispatcherClient(project()));
            WorkerResult result = adapter.bootstrap(project(), countOnlyPiWorker(), new DispatchConfig.TaskTimeout(900, 120), new TaskCancellation());

            assertInstanceOf(WorkerResult.BootstrapComplete.class, result);
            assertEquals(true, Files.exists(tempDir.resolve("output/live-analysis/proj_001/llm-counts/summary.json")));
            assertEquals(true, Files.exists(tempDir.resolve("output/live-analysis/proj_001/llm-counts/events.jsonl")));
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void reasonWritesGraphSnapshotReferenceLikeCairn() {
        CapturingDocker docker = new CapturingDocker(
                new ProcessOutput(0, "{\"accepted\":true,\"data\":{\"intents\":[{\"from\":[\"f001\"],\"description\":\"next step\"}]}}", "")
        );
        CapturingDriver driver = new CapturingDriver();
        ExternalCliWorkerAdapter adapter = new ExternalCliWorkerAdapter(null, docker, new PromptFactory("default"), driver, null, new NoopDispatcherClient(projectWithFinding()));

        WorkerResult result = adapter.reason(projectWithFinding(), worker(), new DispatchConfig.ReasonTask(30, 2), new TaskCancellation());

        assertInstanceOf(WorkerResult.Intents.class, result);
        assertNotNull(docker.writtenPath);
        assertTrue(docker.writtenPath.startsWith("/tmp/cairn-prompts/reason_execute-"));
        assertNotNull(docker.writtenContent);
        assertTrue(docker.writtenContent.contains("exported_graph: true"));
        assertNotNull(driver.prompt);
        assertTrue(driver.prompt.contains("Before using the graph, read the entire file"));
        assertTrue(driver.prompt.contains(docker.writtenPath));
        assertFalse(driver.prompt.contains("ProjectDetail("));
    }

    private ProjectDetail project() {
        return new ProjectDetail(
                new ProjectMeta("proj_001", "proj_001", "active", true, "2026-06-05T00:00:00Z", null),
                List.of(new Fact("origin", "origin"), new Fact("goal", "goal")),
                List.of(new Intent("i001", List.of("origin"), null, "bootstrap", "dispatcher.bootstrap", null, null, "2026-06-05T00:00:00Z", null)),
                List.of(new Hint("h001", "hint", "user", "2026-06-05T00:00:00Z"))
        );
    }

    private ProjectDetail projectWithFinding() {
        return new ProjectDetail(
                new ProjectMeta("proj_001", "proj_001", "active", true, "2026-06-05T00:00:00Z", null),
                List.of(
                        new Fact("origin", "origin"),
                        new Fact("goal", "goal"),
                        new Fact("f001", "native loader identified")
                ),
                List.of(new Intent("i001", List.of("f001"), null, "trace JNI", "dispatcher.reason", null, null, "2026-06-05T00:00:00Z", null)),
                List.of(new Hint("h001", "hint", "user", "2026-06-05T00:00:00Z"))
        );
    }

    private DispatchConfig.WorkerConfig worker() {
        return new DispatchConfig.WorkerConfig("w1", "codex", List.of("bootstrap", "reason", "explore"), 1, 0, Map.of(
                "CODEX_MODEL", "model",
                "CODEX_BASE_URL", "https://example.com",
                "OPENAI_API_KEY", "test-api-key"
        ));
    }

    private DispatchConfig.WorkerConfig piWorker() {
        return new DispatchConfig.WorkerConfig("pi", "pi", List.of("bootstrap", "reason", "explore"), 1, 0, Map.of(
                "PI_MODEL", "glm-5.1",
                "PI_BASE_URL", "https://example.com",
                "PI_API_KEY", "test-api-key",
                "PI_PROVIDER_API", "openai-completions"
        ));
    }

    private DispatchConfig.WorkerConfig countOnlyPiWorker() {
        return new DispatchConfig.WorkerConfig("pi_count_only", "pi", List.of("bootstrap", "reason", "explore"), 1, 0, Map.of(
                "PI_MODEL", "glm-5.1",
                "PI_BASE_URL", "https://example.com",
                "PI_API_KEY", "test-api-key",
                "PI_PROVIDER_API", "openai-completions",
                "R0RE_LLM_COUNT_MODE", "success"
        ));
    }

    private static class FakeDocker extends DockerCli {
        private final Queue<ProcessOutput> outputs = new ArrayDeque<>();
        private final List<String> phases = new ArrayList<>();

        private FakeDocker(ProcessOutput... outputs) {
            super(null, false);
            this.outputs.addAll(List.of(outputs));
        }

        @Override
        public String ensureRunning(String projectId) {
            return "container";
        }

        @Override
        public ProcessOutput exec(String container, Map<String, String> env, List<String> command, String stdin, Duration timeout) {
            phases.add(command.get(0));
            return outputs.remove();
        }

        @Override
        public ProcessOutput exec(String container, Map<String, String> env, List<String> command, String stdin, Duration timeout, StreamStopCondition stopCondition) {
            phases.add(command.get(0));
            return outputs.remove();
        }

        @Override
        public ProcessOutput exec(String container, Map<String, String> env, List<String> command, String stdin, Duration timeout,
                                  StreamStopCondition stopCondition, ExecWatchdog watchdog) {
            phases.add(command.get(0));
            return outputs.remove();
        }
    }

    private static class FakeDriver implements WorkerDriver {
        @Override
        public List<String> buildHealthcheck(DispatchConfig.WorkerConfig worker) {
            return List.of("healthcheck");
        }

        @Override
        public DriverCommand buildExecute(DispatchConfig.WorkerConfig worker, String prompt, String session) {
            return new DriverCommand(List.of("execute"), "sess-1");
        }

        @Override
        public List<String> buildConclude(DispatchConfig.WorkerConfig worker, String prompt, String session) {
            return List.of("conclude");
        }
    }

    private static final class CapturingDriver extends FakeDriver {
        private String prompt;

        @Override
        public DriverCommand buildExecute(DispatchConfig.WorkerConfig worker, String prompt, String session) {
            this.prompt = prompt;
            return new DriverCommand(List.of("execute"), "sess-1");
        }
    }

    private static final class NoDockerFakeDocker extends DockerCli {
        private NoDockerFakeDocker() {
            super(null, false);
        }

        @Override
        public String ensureRunning(String projectId) {
            throw new AssertionError("count-only mode should not start containers");
        }
    }

    private static final class CapturingDocker extends FakeDocker {
        private String writtenPath;
        private String writtenContent;

        private CapturingDocker(ProcessOutput... outputs) {
            super(outputs);
        }

        @Override
        public void writeFile(String container, String path, String content) {
            this.writtenPath = path;
            this.writtenContent = content;
        }
    }

    private static final class NoopDispatcherClient extends DispatcherClient {
        private final ProjectDetail project;

        private NoopDispatcherClient(ProjectDetail project) {
            super("http://127.0.0.1:1");
            this.project = project;
        }

        @Override
        public ProjectDetail project(String id) {
            return project;
        }

        @Override
        public String export(String projectId, String format) {
            return "exported_graph: true\nproject_id: " + projectId + "\n";
        }

        @Override
        public com.fyr.r0re.protocol.Models.RuntimeEvent appendRuntimeEvent(String projectId, com.fyr.r0re.protocol.Models.CreateRuntimeEventRequest body) {
            return null;
        }
    }
}
