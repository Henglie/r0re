package com.fyr.r0re.dispatcher;

import com.fyr.r0re.config.R0reProperties;
import com.fyr.r0re.protocol.Models.Fact;
import com.fyr.r0re.protocol.Models.Intent;
import com.fyr.r0re.protocol.Models.PhaseRuntimeState;
import com.fyr.r0re.protocol.Models.ProjectDetail;
import com.fyr.r0re.protocol.Models.ProjectMeta;
import com.fyr.r0re.protocol.Models.ProjectReason;
import com.fyr.r0re.protocol.Models.ProjectRuntime;
import com.fyr.r0re.protocol.Models.ProjectSummary;
import com.fyr.r0re.protocol.Models.RuntimeEvent;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DispatcherRunnerTest {
    @Test
    void kimiSwarmExampleConfigUsesKimiWorkerWithThreeWorkerRuntime() throws Exception {
        DispatchConfig config = new YAMLMapper().readValue(
                Path.of("dispatch.kimi.swarm.example.yaml").toFile(),
                DispatchConfig.class
        );

        assertEquals("android-reverse", config.runtime().prompt_group());
        assertEquals("swarm", config.runtime().mode());
        assertEquals(3, config.runtime().max_workers());
        assertEquals(3, config.runtime().max_project_workers());
        assertEquals("startup_only", config.runtime().worker_healthcheck());
        assertTrue(config.container().volumes().containsKey("${R0RE_WORKSPACE}/container-android/test_apk"));
        assertTrue(config.container().volumes().containsKey("${R0RE_WORKSPACE}/output/live-analysis/{project_id}"));

        DispatchConfig.WorkerConfig worker = config.workers().get(0);
        assertEquals("kimi_swarm", worker.name());
        assertEquals("kimi", worker.type());
        assertEquals(3, worker.max_running());
        assertEquals("${KIMI_MODEL_API_KEY}", worker.env().get("KIMI_MODEL_API_KEY"));
        assertEquals("/tmp/r0re-kimi", worker.env().get("KIMI_CODE_HOME"));
        assertTrue(worker.supports("bootstrap"));
        assertTrue(worker.supports("reason"));
        assertTrue(worker.supports("explore"));
    }

    @Test
    void verifierIdentityUsesAndroidCtfVerifierForAndroidReverseProjects() throws Exception {
        DispatcherRunner runner = new DispatcherRunner(new R0reProperties(), null);

        Method verifierIdentity = DispatcherRunner.class.getDeclaredMethod("verifierIdentity", ProjectDetail.class, DispatchConfig.class);
        verifierIdentity.setAccessible(true);

        ProjectMeta meta = new ProjectMeta(
                "proj_001",
                "Android crackme verifier",
                "active",
                true,
                "2026-06-25T00:00:00Z",
                null
        );
        ProjectDetail project = new ProjectDetail(
                meta,
                List.of(
                        new Fact("origin", "Reverse the APK native checker"),
                        new Fact("goal", "Recover the accepted flag")
                ),
                List.of(),
                List.of()
        );
        DispatchConfig config = new DispatchConfig(
                "http://127.0.0.1:8001",
                null,
                new DispatchConfig.RuntimeConfig(3, 1, 1, 1, 30, "disabled", "android-reverse", null),
                new DispatchConfig.VerifierConfig("r0re.rule-verifier", "reverse-verifier", "android-ctf-verifier", "rule-verifier", "verify", "reverse-verifier", "android-ctf-verifier"),
                null,
                null,
                List.of()
        );

        Object identity = verifierIdentity.invoke(runner, project, config);
        Method worker = identity.getClass().getDeclaredMethod("worker");
        Method promptGroup = identity.getClass().getDeclaredMethod("promptGroup");
        worker.setAccessible(true);
        promptGroup.setAccessible(true);

        assertEquals("android-ctf-verifier", worker.invoke(identity));
        assertEquals("android-ctf-verifier", promptGroup.invoke(identity));
    }

    @Test
    void refreshRuntimeProjectsDropsStaleProjectIdsWithoutRunningTasks() throws Exception {
        DispatcherRunner runner = new DispatcherRunner(new R0reProperties(), null);

        Field runtimeProjectIds = DispatcherRunner.class.getDeclaredField("runtimeProjectIds");
        runtimeProjectIds.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> ids = (Set<String>) runtimeProjectIds.get(runner);
        ids.add("proj_001");

        Method refreshRuntimeProjects = DispatcherRunner.class.getDeclaredMethod("refreshRuntimeProjects", List.class);
        refreshRuntimeProjects.setAccessible(true);
        refreshRuntimeProjects.invoke(runner, List.of(new ProjectSummary(
                "proj_001", "title", "active", true, "2026-06-11T00:00:00Z", null, 2, 1, 0, 0, 0
        )));

        assertEquals(Set.of(), ids);
    }

    @Test
    void historicalActiveProjectIsNotSchedulableOnDispatcherStartup() throws Exception {
        DispatcherRunner runner = new DispatcherRunner(new R0reProperties(), null);
        Method isSessionSchedulableProject = DispatcherRunner.class.getDeclaredMethod(
                "isSessionSchedulableProject",
                DispatcherClient.class,
                ProjectSummary.class
        );
        isSessionSchedulableProject.setAccessible(true);

        ProjectMeta meta = new ProjectMeta("proj_033", "old active", "active", true, "2026-07-01T00:00:00Z", null);
        ProjectRuntime runtime = new ProjectRuntime("harness-loop.v1", meta, List.of(), List.of(), null, List.of(), List.of(), List.of());
        RecordingDispatcherClient client = new RecordingDispatcherClient(new ProjectDetail(meta, List.of(), List.of(), List.of()), runtime);

        boolean schedulable = (boolean) isSessionSchedulableProject.invoke(
                runner,
                client,
                new ProjectSummary("proj_033", "old active", "active", true, "2026-07-01T00:00:00Z", null, 2, 0, 0, 0, 0)
        );

        assertFalse(schedulable);
    }

    @Test
    void historicalActiveProjectBecomesSchedulableAfterManualResumeEvent() throws Exception {
        DispatcherRunner runner = new DispatcherRunner(new R0reProperties(), null);
        Method isSessionSchedulableProject = DispatcherRunner.class.getDeclaredMethod(
                "isSessionSchedulableProject",
                DispatcherClient.class,
                ProjectSummary.class
        );
        isSessionSchedulableProject.setAccessible(true);

        ProjectMeta meta = new ProjectMeta("proj_033", "old active", "active", true, "2026-07-01T00:00:00Z", null);
        RuntimeEvent resume = new RuntimeEvent(
                1,
                "proj_033",
                "project",
                "proj_033",
                "WebUI",
                "project_execution_resumed",
                "resume",
                "manual_resume",
                "execution resumed from WebUI",
                null,
                null,
                null,
                null,
                Instant.now().toString()
        );
        ProjectRuntime runtime = new ProjectRuntime("harness-loop.v1", meta, List.of(), List.of(), null, List.of(resume), List.of(), List.of());
        RecordingDispatcherClient client = new RecordingDispatcherClient(new ProjectDetail(meta, List.of(), List.of(), List.of()), runtime);

        boolean schedulable = (boolean) isSessionSchedulableProject.invoke(
                runner,
                client,
                new ProjectSummary("proj_033", "old active", "active", true, "2026-07-01T00:00:00Z", null, 2, 0, 0, 0, 0)
        );

        assertTrue(schedulable);
    }

    @Test
    void newlyCreatedActiveProjectIsSchedulableInCurrentDispatcherSession() throws Exception {
        DispatcherRunner runner = new DispatcherRunner(new R0reProperties(), null);
        Method isSessionSchedulableProject = DispatcherRunner.class.getDeclaredMethod(
                "isSessionSchedulableProject",
                DispatcherClient.class,
                ProjectSummary.class
        );
        isSessionSchedulableProject.setAccessible(true);

        ProjectMeta meta = new ProjectMeta("proj_040", "new active", "active", true, Instant.now().toString(), null);
        ProjectRuntime runtime = new ProjectRuntime("harness-loop.v1", meta, List.of(), List.of(), null, List.of(), List.of(), List.of());
        RecordingDispatcherClient client = new RecordingDispatcherClient(new ProjectDetail(meta, List.of(), List.of(), List.of()), runtime);

        boolean schedulable = (boolean) isSessionSchedulableProject.invoke(
                runner,
                client,
                new ProjectSummary("proj_040", "new active", "active", true, meta.created_at(), null, 2, 0, 0, 0, 0)
        );

        assertTrue(schedulable);
    }

    @Test
    void mergeCommonEnvResolvesEnvironmentPlaceholders() throws Exception {
        DispatcherRunner runner = new DispatcherRunner(new R0reProperties(), null);
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return;
        }

        Method mergeCommonEnv = DispatcherRunner.class.getDeclaredMethod("mergeCommonEnv", DispatchConfig.class);
        mergeCommonEnv.setAccessible(true);

        DispatchConfig config = new DispatchConfig(
                "http://127.0.0.1:8001",
                Map.of("OPENAI_API_KEY", "${PATH}"),
                new DispatchConfig.RuntimeConfig(3, 1, 1, 1, 30, "disabled", "android-reverse", null),
                null,
                null,
                null,
                List.of(new DispatchConfig.WorkerConfig(
                        "codex_openai",
                        "codex",
                        List.of("bootstrap"),
                        1,
                        0,
                        Map.of("CODEX_MODEL", "gpt-5.5")
                ))
        );

        DispatchConfig merged = (DispatchConfig) mergeCommonEnv.invoke(runner, config);

        assertEquals(path, merged.workers().get(0).env().get("OPENAI_API_KEY"));
        assertEquals("gpt-5.5", merged.workers().get(0).env().get("CODEX_MODEL"));
    }

    @Test
    void selectWorkerSkipsWorkersInUnhealthyCooldownWindow() throws Exception {
        DispatcherRunner runner = new DispatcherRunner(new R0reProperties(), null);

        Field workerUnhealthyUntil = DispatcherRunner.class.getDeclaredField("workerUnhealthyUntil");
        workerUnhealthyUntil.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Long> unhealthyUntil = (Map<String, Long>) workerUnhealthyUntil.get(runner);
        unhealthyUntil.put("codex_chatgpt", System.currentTimeMillis() + 10_000L);

        Method selectWorker = DispatcherRunner.class.getDeclaredMethod("selectWorker", DispatchConfig.class, String.class, String.class);
        selectWorker.setAccessible(true);

        DispatchConfig config = new DispatchConfig(
                "http://127.0.0.1:8001",
                null,
                new DispatchConfig.RuntimeConfig(3, 1, 1, 1, 30, "disabled", "android-reverse", null),
                null,
                null,
                null,
                List.of(new DispatchConfig.WorkerConfig(
                        "codex_chatgpt",
                        "codex",
                        List.of("bootstrap"),
                        1,
                        0,
                        Map.of("CODEX_MODEL", "gpt-5.4", "CODEX_BASE_URL", "https://example.com", "OPENAI_API_KEY", "test-api-key")
                ))
        );

        Object selection = selectWorker.invoke(runner, config, "proj_003", "bootstrap");
        Method worker = selection.getClass().getDeclaredMethod("worker");
        worker.setAccessible(true);

        assertNull(worker.invoke(selection));
    }

    @Test
    void tryDispatchProjectPrioritizesReasonTriggerBeforeOpenIntent() throws Exception {
        DispatcherRunner runner = new DispatcherRunner(new R0reProperties(), null);

        Field taskExecutor = DispatcherRunner.class.getDeclaredField("taskExecutor");
        taskExecutor.setAccessible(true);
        taskExecutor.set(runner, new NoOpExecutorService());

        Field reasonCheckpoints = DispatcherRunner.class.getDeclaredField("reasonCheckpoints");
        reasonCheckpoints.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ReasonCheckpoint> checkpoints = (Map<String, ReasonCheckpoint>) reasonCheckpoints.get(runner);
        checkpoints.put("proj_015", new ReasonCheckpoint(4, 0, 1));

        Method tryDispatchProject = DispatcherRunner.class.getDeclaredMethod(
                "tryDispatchProject",
                DispatchConfig.class,
                DispatcherClient.class,
                ProjectSummary.class
        );
        tryDispatchProject.setAccessible(true);

        ProjectMeta meta = new ProjectMeta(
                "proj_015",
                "AliCrackme2 DeepSeek native run",
                "active",
                true,
                "2026-06-17T02:20:45Z",
                null
        );
        Intent openIntent = new Intent(
                "i004",
                List.of("origin", "f001", "f002"),
                null,
                "Reverse engineer SMC-decrypted validation code at 0x24c8",
                "pi_deepseek",
                null,
                null,
                "2026-06-17T06:38:18Z",
                null
        );
        ProjectDetail project = new ProjectDetail(
                meta,
                List.of(
                        new Fact("origin", "origin"),
                        new Fact("goal", "goal"),
                        new Fact("f001", "wrong answer"),
                        new Fact("f002", "need deeper native analysis")
                ),
                List.of(openIntent),
                List.of(new com.fyr.r0re.protocol.Models.Hint("h001", "user says previous answer is wrong", "user", "2026-06-17T06:40:00Z"))
        );
        ProjectRuntime runtime = new ProjectRuntime(
                "harness-loop.v1",
                meta,
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(new PhaseRuntimeState("intent", "i004", "verify", null, null, null, null, true)),
                List.of()
        );
        RecordingDispatcherClient client = new RecordingDispatcherClient(project, runtime);

        DispatchConfig config = new DispatchConfig(
                "http://127.0.0.1:8001",
                null,
                new DispatchConfig.RuntimeConfig(3, 2, 2, 1, 30, "disabled", "android-reverse", null),
                null,
                null,
                null,
                List.of(new DispatchConfig.WorkerConfig(
                        "pi_deepseek",
                        "mock",
                        List.of("explore", "reason"),
                        1,
                        0,
                        Map.of()
                ))
        );

        boolean dispatched = (boolean) tryDispatchProject.invoke(
                runner,
                config,
                client,
                new ProjectSummary("proj_015", "AliCrackme2 DeepSeek native run", "active", true, "2026-06-17T02:20:45Z", null, 4, 1, 0, 1, 0)
        );

        assertTrue(dispatched);
        assertEquals(List.of(), client.heartbeatClaims);
        assertEquals(List.of("hints:0->1"), client.reasonClaims);
    }

    @Test
    void tryDispatchProjectDoesNotRedispatchReportWithoutGraphChanges() throws Exception {
        DispatcherRunner runner = new DispatcherRunner(new R0reProperties(), null);

        Field taskExecutor = DispatcherRunner.class.getDeclaredField("taskExecutor");
        taskExecutor.setAccessible(true);
        taskExecutor.set(runner, new NoOpExecutorService());

        Field reasonCheckpoints = DispatcherRunner.class.getDeclaredField("reasonCheckpoints");
        reasonCheckpoints.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ReasonCheckpoint> checkpoints = (Map<String, ReasonCheckpoint>) reasonCheckpoints.get(runner);
        checkpoints.put("proj_031", new ReasonCheckpoint(4, 1, 3));

        Method tryDispatchProject = DispatcherRunner.class.getDeclaredMethod(
                "tryDispatchProject",
                DispatchConfig.class,
                DispatcherClient.class,
                ProjectSummary.class
        );
        tryDispatchProject.setAccessible(true);

        ProjectMeta meta = new ProjectMeta(
                "proj_031",
                "Godot report loop",
                "active",
                true,
                "2026-07-01T00:00:00Z",
                null
        );
        ProjectDetail project = new ProjectDetail(
                meta,
                List.of(
                        new Fact("origin", "origin"),
                        new Fact("goal", "goal"),
                        new Fact("f001", "writeup mentions report and document"),
                        new Fact("f002", "native analysis still blocked")
                ),
                List.of(
                        new Intent("i005", List.of("f001"), null, "decrypt token.gdc", null, null, null, "2026-07-01T00:10:00Z", null),
                        new Intent("i006", List.of("f002"), null, "recover key source", null, null, null, "2026-07-01T00:11:00Z", null),
                        new Intent("i007", List.of("f002"), null, "write report after decryption", null, null, null, "2026-07-01T00:12:00Z", null)
                ),
                List.of(new com.fyr.r0re.protocol.Models.Hint("h001", "report later after execution", "user", "2026-07-01T00:13:00Z"))
        );
        ProjectRuntime runtime = new ProjectRuntime(
                "harness-loop.v1",
                meta,
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of()
        );
        RecordingDispatcherClient client = new RecordingDispatcherClient(project, runtime);

        DispatchConfig config = new DispatchConfig(
                "http://127.0.0.1:8001",
                null,
                new DispatchConfig.RuntimeConfig(3, 2, 2, 1, 30, "disabled", "android-reverse", null),
                null,
                null,
                null,
                List.of(new DispatchConfig.WorkerConfig(
                        "pi_glm51_aiproxy",
                        "mock",
                        List.of("explore", "reason"),
                        1,
                        0,
                        Map.of()
                ))
        );

        boolean dispatched = (boolean) tryDispatchProject.invoke(
                runner,
                config,
                client,
                new ProjectSummary("proj_031", "Godot report loop", "active", true, "2026-07-01T00:00:00Z", null, 4, 3, 0, 1, 0)
        );

        assertTrue(dispatched);
        assertEquals(List.of(), client.reasonClaims);
        assertEquals(List.of("i007"), client.heartbeatClaims);
    }

    @Test
    void ensureStandaloneInvocationRejectsEmbeddedDispatcherByDefault() throws Exception {
        R0reProperties properties = new R0reProperties();
        properties.getDispatcher().setEnabled(true);
        DispatcherRunner runner = new DispatcherRunner(properties, null);

        Method ensureStandaloneInvocation = DispatcherRunner.class.getDeclaredMethod("ensureStandaloneInvocation");
        ensureStandaloneInvocation.setAccessible(true);

        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> ensureStandaloneInvocation.invoke(runner));
    }

    @Test
    void ensureStandaloneInvocationAllowsStandaloneDispatchMode() throws Exception {
        R0reProperties properties = new R0reProperties();
        properties.getDispatcher().setEnabled(true);
        properties.getDispatcher().setStandaloneInvocation(true);
        DispatcherRunner runner = new DispatcherRunner(properties, null);

        Method ensureStandaloneInvocation = DispatcherRunner.class.getDeclaredMethod("ensureStandaloneInvocation");
        ensureStandaloneInvocation.setAccessible(true);

        ensureStandaloneInvocation.invoke(runner);
    }

    @Test
    void dispatchBootstrapDoesNotClaimWhenHeavyCapacityBlocked() throws Exception {
        DispatcherRunner runner = new DispatcherRunner(new R0reProperties(), null);

        Field taskExecutor = DispatcherRunner.class.getDeclaredField("taskExecutor");
        taskExecutor.setAccessible(true);
        taskExecutor.set(runner, new NoOpExecutorService());

        Field futuresField = DispatcherRunner.class.getDeclaredField("futures");
        futuresField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Future<String>, RunningTask> futures = (Map<Future<String>, RunningTask>) futuresField.get(runner);
        futures.put(
                CompletableFuture.completedFuture("running"),
                new RunningTask(
                        "proj_other",
                        "explore",
                        "pi_deepseek",
                        new TaskCancellation(),
                        "i999",
                        "native_entry",
                        "heavy",
                        "running",
                        "2026-06-18T00:00:00Z",
                        "2026-06-18T00:00:00Z",
                        0,
                        null,
                        "so",
                        0,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );

        Method dispatchBootstrap = DispatcherRunner.class.getDeclaredMethod(
                "dispatchBootstrap",
                DispatchConfig.class,
                DispatcherClient.class,
                ProjectDetail.class
        );
        dispatchBootstrap.setAccessible(true);

        ProjectMeta meta = new ProjectMeta(
                "proj_018",
                "0618",
                "active",
                true,
                "2026-06-18T02:12:08Z",
                null
        );
        Intent bootstrapIntent = new Intent(
                "i001",
                List.of("origin"),
                null,
                "bootstrap",
                "dispatcher.bootstrap",
                null,
                null,
                "2026-06-18T02:12:11Z",
                null
        );
        ProjectDetail project = new ProjectDetail(
                meta,
                List.of(
                        new Fact("origin", "origin"),
                        new Fact("goal", "goal")
                ),
                List.of(bootstrapIntent),
                List.of()
        );
        ProjectRuntime runtime = new ProjectRuntime("harness-loop.v1", meta, List.of(), List.of(), null, List.of(), List.of(), List.of());
        RecordingDispatcherClient client = new RecordingDispatcherClient(project, runtime);

        DispatchConfig config = new DispatchConfig(
                "http://127.0.0.1:8001",
                null,
                new DispatchConfig.RuntimeConfig(3, 2, 2, 1, 30, "disabled", "android-reverse", null),
                null,
                null,
                null,
                List.of(new DispatchConfig.WorkerConfig(
                        "pi_deepseek",
                        "mock",
                        List.of("bootstrap"),
                        1,
                        0,
                        Map.of()
                ))
        );

        boolean dispatched = (boolean) dispatchBootstrap.invoke(runner, config, client, project);

        assertTrue(!dispatched);
        assertEquals(List.of(), client.heartbeatClaims);
    }

    private static final class RecordingDispatcherClient extends DispatcherClient {
        private final ProjectDetail project;
        private final ProjectRuntime runtime;
        private final List<String> heartbeatClaims = new java.util.ArrayList<>();
        private final List<String> reasonClaims = new java.util.ArrayList<>();

        private RecordingDispatcherClient(ProjectDetail project, ProjectRuntime runtime) {
            super("http://127.0.0.1:8001");
            this.project = project;
            this.runtime = runtime;
        }

        @Override
        public ProjectDetail project(String id) {
            return project;
        }

        @Override
        public ProjectRuntime projectRuntime(String id) {
            return runtime;
        }

        @Override
        public ApiResult heartbeatResult(String projectId, String intentId, String worker) {
            heartbeatClaims.add(intentId);
            return new ApiResult(true, 200, "");
        }

        @Override
        public ApiResult claimReason(String projectId, String worker, String trigger) {
            reasonClaims.add(trigger);
            return new ApiResult(true, 200, "");
        }

        @Override
        public com.fyr.r0re.protocol.Models.RuntimeEvent appendRuntimeEvent(String projectId, com.fyr.r0re.protocol.Models.CreateRuntimeEventRequest body) {
            return null;
        }
    }

    private static final class NoOpExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
        }
    }
}
