package com.fyr.r0re.dispatcher;

import com.fyr.r0re.config.R0reProperties;
import com.fyr.r0re.protocol.Models.CompletionCandidate;
import com.fyr.r0re.protocol.Models.Fact;
import com.fyr.r0re.protocol.Models.Intent;
import com.fyr.r0re.protocol.Models.ProjectDetail;
import com.fyr.r0re.protocol.Models.ProjectHealth;
import com.fyr.r0re.protocol.Models.ProjectMeta;
import com.fyr.r0re.protocol.Models.ProjectSummary;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatcherRunnerSwarmTest {
    @Test
    void swarmInitialProjectDispatchesParallelRaceTasks() throws Exception {
        DispatcherRunner runner = newRunner();
        ProjectDetail project = initialProject("proj_050");
        RecordingDispatcherClient client = new RecordingDispatcherClient(project);
        DispatchConfig config = swarmConfig(4, 3, 3);

        boolean dispatched = (boolean) tryDispatchProject(runner).invoke(
                runner, config, client, summary("proj_050", 2, 0, 0, 0));

        assertTrue(dispatched);
        assertEquals(3, futures(runner).size());
        assertEquals(List.of(), client.heartbeatClaims);
        assertTrue(swarmRaceStarted(runner).contains("proj_050"));
        for (RunningTask task : futures(runner).values()) {
            assertEquals("race", task.taskType());
            assertEquals("kimi", task.workerName());
        }
    }

    @Test
    void swarmRaceFinishWithoutFactsWritesDispatcherBoardFact() throws Exception {
        DispatcherRunner runner = newRunner();
        ProjectDetail project = initialProject("proj_051");
        RecordingDispatcherClient client = new RecordingDispatcherClient(project);
        DispatchConfig config = swarmConfig(4, 3, 3);

        boolean dispatched = (boolean) tryDispatchProject(runner).invoke(
                runner, config, client, summary("proj_051", 2, 0, 0, 0));
        assertTrue(dispatched);
        assertEquals(3, futures(runner).size());

        // simulate all race tasks reaped without any board output
        futures(runner).clear();

        boolean second = (boolean) tryDispatchProject(runner).invoke(
                runner, config, client, summary("proj_051", 2, 0, 0, 0));

        assertTrue(!second);
        assertEquals(0, futures(runner).size());
        assertEquals(1, client.blackboardFacts.size());
        assertEquals("r0re.dispatcher", client.blackboardFacts.get(0).worker);
        assertTrue(client.blackboardFacts.get(0).description.contains("race phase finished"));
        assertTrue(!swarmRaceStarted(runner).contains("proj_051"));
    }

    @Test
    void swarmExploreDispatchesOnePerTickWithoutPreClaim() throws Exception {
        DispatcherRunner runner = newRunner();
        Intent older = new Intent("i010", List.of("f001"), null, "map JNI bridge", "reason", null, null, "2026-07-22T00:10:00Z", null);
        Intent newer = new Intent("i011", List.of("f001"), null, "recover xor key", "reason", null, null, "2026-07-22T00:11:00Z", null);
        ProjectDetail project = new ProjectDetail(
                new ProjectMeta("proj_052", "swarm explore", "active", true, "2026-07-22T00:00:00Z", null),
                List.of(
                        new Fact("origin", "origin"),
                        new Fact("goal", "goal"),
                        new Fact("f001", "race phase finished: no conclusive result, entering coordinator loop")
                ),
                List.of(older, newer),
                List.of()
        );
        RecordingDispatcherClient client = new RecordingDispatcherClient(project);
        reasonCheckpoints(runner).put("proj_052", new ReasonCheckpoint(3, 0, 2));
        DispatchConfig config = swarmConfig(4, 2, 2);

        boolean dispatched = (boolean) tryDispatchProject(runner).invoke(
                runner, config, client, summary("proj_052", 3, 0, 0, 2));

        assertTrue(dispatched);
        assertEquals(1, futures(runner).size());
        // no pre-claim: dispatcher must not heartbeat/claim the intent
        assertEquals(List.of(), client.heartbeatClaims);
        assertEquals(List.of(), client.reasonClaims);
        RunningTask task = futures(runner).values().iterator().next();
        assertEquals("explore", task.taskType());
        assertEquals("i011", task.intentId());
        assertEquals("kimi", task.workerName());
        // intent stays unclaimed on the board until a swarm worker claims it
        assertNull(newer.worker());
        assertNull(older.worker());
    }

    @Test
    void swarmIntentHeartbeatOnlyBeatsIntentsOwnedByWorkerName() {
        Intent owned = new Intent("i001", List.of("f001"), null, "map JNI bridge", "reason", "kimi-abcd1234", null, "2026-07-22T00:10:00Z", null);
        Intent unclaimed = new Intent("i002", List.of("f001"), null, "recover key", "reason", null, null, "2026-07-22T00:11:00Z", null);
        Intent concluded = new Intent("i003", List.of("f001"), "f009", "done", "reason", "kimi-abcd1234", null, "2026-07-22T00:12:00Z", "2026-07-22T00:13:00Z");
        ProjectDetail project = new ProjectDetail(
                new ProjectMeta("proj_053", "swarm heartbeat", "active", true, "2026-07-22T00:00:00Z", null),
                List.of(new Fact("origin", "origin"), new Fact("goal", "goal"), new Fact("f001", "fact")),
                List.of(owned, unclaimed, concluded),
                List.of()
        );
        RecordingDispatcherClient client = new RecordingDispatcherClient(project);

        DispatcherRunner.SwarmIntentHeartbeat heartbeat =
                new DispatcherRunner.SwarmIntentHeartbeat(client, "proj_053", "kimi-abcd1234", 30, new TaskCancellation());
        heartbeat.beatOnce();

        assertEquals(List.of("i001"), client.heartbeatClaims);
    }

    @Test
    void swarmAutoResumeOnlyTargetsRecoveryRequiredProjects() throws Exception {
        DispatcherRunner runner = newRunner();
        // 手动 stop 的项目:health 保持 normal,绝不能被自动恢复
        ProjectDetail manualStopped = new ProjectDetail(
                new ProjectMeta("proj_060", "manual stop", "stopped", true, "2026-07-22T00:00:00Z", null),
                List.of(new Fact("origin", "origin"), new Fact("goal", "goal")),
                List.of(),
                List.of()
        );
        // 租约过期被系统强制停摆的项目:health = recovery_required,应被自动恢复
        ProjectDetail leaseStopped = new ProjectDetail(
                new ProjectMeta("proj_061", "lease expired", "stopped", true, "2026-07-22T00:00:00Z", null),
                List.of(new Fact("origin", "origin"), new Fact("goal", "goal")),
                List.of(),
                List.of()
        );
        RecordingDispatcherClient client = new RecordingDispatcherClient(manualStopped);
        client.projects.put("proj_060", manualStopped);
        client.projects.put("proj_061", leaseStopped);
        client.healths.put("proj_060", new ProjectHealth("proj_060", "normal", "none", null, null, "2026-07-22T00:01:00Z"));
        client.healths.put("proj_061", new ProjectHealth("proj_061", "recovery_required", "none", null, null, "2026-07-22T00:01:00Z"));
        DispatchConfig config = swarmConfig(3, 3, 3);

        Method dispatchAvailable = DispatcherRunner.class.getDeclaredMethod(
                "dispatchAvailable", DispatchConfig.class, DispatcherClient.class, List.class);
        dispatchAvailable.setAccessible(true);
        dispatchAvailable.invoke(runner, config, client, List.of(
                summary("proj_060", 2, 0, 0, 0, "stopped"),
                summary("proj_061", 2, 0, 0, 0, "stopped")
        ));

        assertEquals(List.of("proj_061"), client.resumes);
    }

    private static DispatcherRunner newRunner() throws Exception {
        DispatcherRunner runner = new DispatcherRunner(new R0reProperties(), null);
        Field taskExecutor = DispatcherRunner.class.getDeclaredField("taskExecutor");
        taskExecutor.setAccessible(true);
        taskExecutor.set(runner, new NoOpExecutorService());
        return runner;
    }

    private static Method tryDispatchProject(DispatcherRunner runner) throws Exception {
        Method method = DispatcherRunner.class.getDeclaredMethod(
                "tryDispatchProject", DispatchConfig.class, DispatcherClient.class, ProjectSummary.class);
        method.setAccessible(true);
        return method;
    }

    @SuppressWarnings("unchecked")
    private static Map<Future<String>, RunningTask> futures(DispatcherRunner runner) throws Exception {
        Field field = DispatcherRunner.class.getDeclaredField("futures");
        field.setAccessible(true);
        return (Map<Future<String>, RunningTask>) field.get(runner);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> swarmRaceStarted(DispatcherRunner runner) throws Exception {
        Field field = DispatcherRunner.class.getDeclaredField("swarmRaceStarted");
        field.setAccessible(true);
        return (Set<String>) field.get(runner);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ReasonCheckpoint> reasonCheckpoints(DispatcherRunner runner) throws Exception {
        Field field = DispatcherRunner.class.getDeclaredField("reasonCheckpoints");
        field.setAccessible(true);
        return (Map<String, ReasonCheckpoint>) field.get(runner);
    }

    private static ProjectDetail initialProject(String projectId) {
        return new ProjectDetail(
                new ProjectMeta(projectId, "swarm race", "active", true, "2026-07-22T00:00:00Z", null),
                List.of(new Fact("origin", "origin"), new Fact("goal", "goal")),
                List.of(),
                List.of()
        );
    }

    private static ProjectSummary summary(String projectId, int factCount, int workingIntents, int unclaimedIntents, int hintCount) {
        return summary(projectId, factCount, workingIntents, unclaimedIntents, hintCount, "active");
    }

    private static ProjectSummary summary(String projectId, int factCount, int workingIntents, int unclaimedIntents, int hintCount, String status) {
        return new ProjectSummary(projectId, "swarm", status, true, "2026-07-22T00:00:00Z", null,
                factCount, workingIntents + unclaimedIntents, workingIntents, unclaimedIntents, hintCount);
    }

    private static DispatchConfig swarmConfig(int maxWorkers, int maxProjectWorkers, int workerMaxRunning) {
        return new DispatchConfig(
                "http://127.0.0.1:9",
                null,
                new DispatchConfig.RuntimeConfig(3, maxWorkers, 2, maxProjectWorkers, 30, "disabled", "android-reverse", null, "swarm"),
                null,
                null,
                null,
                List.of(new DispatchConfig.WorkerConfig(
                        "kimi",
                        "mock",
                        List.of("bootstrap", "reason", "explore"),
                        workerMaxRunning,
                        0,
                        Map.of()
                ))
        );
    }

    private static final class RecordedFact {
        private final String worker;
        private final String description;
        private final boolean verified;

        private RecordedFact(String worker, String description, boolean verified) {
            this.worker = worker;
            this.description = description;
            this.verified = verified;
        }
    }

    private static final class RecordingDispatcherClient extends DispatcherClient {
        private final ProjectDetail project;
        private final List<String> heartbeatClaims = new ArrayList<>();
        private final List<String> reasonClaims = new ArrayList<>();
        private final List<RecordedFact> blackboardFacts = new ArrayList<>();
        private final Map<String, ProjectDetail> projects = new java.util.HashMap<>();
        private final Map<String, ProjectHealth> healths = new java.util.HashMap<>();
        private final List<String> resumes = new ArrayList<>();

        private RecordingDispatcherClient(ProjectDetail project) {
            super("http://127.0.0.1:9");
            this.project = project;
        }

        @Override
        public ProjectDetail project(String id) {
            return projects.getOrDefault(id, project);
        }

        @Override
        public ProjectHealth projectHealth(String id) {
            return healths.get(id);
        }

        @Override
        public void resumeExecution(String projectId, String creator) {
            resumes.add(projectId);
        }

        @Override
        public List<CompletionCandidate> completionCandidates(String projectId) {
            return List.of();
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
        public Fact blackboardAddFact(String projectId, String worker, String description, boolean verified) {
            blackboardFacts.add(new RecordedFact(worker, description, verified));
            return new Fact("f900", description);
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
