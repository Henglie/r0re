package com.fyr.r0re.dispatcher;

import com.fyr.r0re.protocol.Models.HeartbeatRequest;
import java.util.concurrent.atomic.AtomicReference;

public class HeartbeatLease {
    public record HeartbeatFailure(Integer statusCode, String text) {
    }

    @FunctionalInterface
    public interface Beat {
        DispatcherClient.ApiResult call(HeartbeatRequest body);
    }

    private static final int GRACE_MULTIPLIER = 2;

    private final Beat beat;
    private final String scope;
    private final String workerName;
    private final int intervalSeconds;
    private final TaskCancellation cancellation;
    private final AtomicReference<HeartbeatFailure> failure = new AtomicReference<>();
    private final AtomicReference<HeartbeatRequest> request = new AtomicReference<>();
    private final Thread thread;
    private volatile boolean stopped;
    private volatile long lastSuccessAt = System.nanoTime();

    public HeartbeatLease(Beat beat, String scope, String workerName, int intervalSeconds, TaskCancellation cancellation) {
        this.beat = beat;
        this.scope = scope;
        this.workerName = workerName;
        this.intervalSeconds = intervalSeconds;
        this.cancellation = cancellation;
        this.thread = new Thread(this::runLoop, "r0re-heartbeat-" + workerName);
        this.thread.setDaemon(true);
    }

    public static HeartbeatLease forIntent(DispatcherClient client, String projectId, String intentId, String workerName, int intervalSeconds, TaskCancellation cancellation) {
        HeartbeatRequest body = new HeartbeatRequest(workerName, "intent_running", "heartbeat", "intent running", null, null, null, null);
        HeartbeatLease lease = new HeartbeatLease(
                request -> client.heartbeatResult(projectId, intentId, request),
                "project=" + projectId + " intent=" + intentId,
                workerName,
                intervalSeconds,
                cancellation
        );
        lease.request.set(body);
        return lease;
    }

    public static HeartbeatLease forReason(DispatcherClient client, String projectId, String workerName, int intervalSeconds, TaskCancellation cancellation) {
        HeartbeatRequest body = new HeartbeatRequest(workerName, "reason_running", "heartbeat", "reason running", null, null, null, null);
        HeartbeatLease lease = new HeartbeatLease(
                request -> client.reasonHeartbeatResult(projectId, request),
                "project=" + projectId + " reason",
                workerName,
                intervalSeconds,
                cancellation
        );
        lease.request.set(body);
        return lease;
    }

    public void update(String phase, String step, String message, String artifact, String tool, Integer progressCurrent, Integer progressTotal) {
        request.set(new HeartbeatRequest(
                workerName,
                phase,
                step,
                message,
                artifact,
                tool,
                progressCurrent,
                progressTotal
        ));
    }

    public void start() {
        thread.start();
    }

    public void stop() {
        stopped = true;
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
    }

    public HeartbeatFailure failure() {
        return failure.get();
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
            if (stopped) {
                return;
            }
            DispatcherClient.ApiResult result = beat.call(currentRequest());
            if (result.ok()) {
                lastSuccessAt = System.nanoTime();
                continue;
            }
            if (result.statusCode() == 403 || result.statusCode() == 409) {
                fail(result.statusCode(), result.responseText());
                return;
            }
            long elapsedMillis = (System.nanoTime() - lastSuccessAt) / 1_000_000L;
            long graceMillis = Math.max(intervalSeconds * 1000L, intervalSeconds * GRACE_MULTIPLIER * 1000L);
            if (elapsedMillis < graceMillis) {
                continue;
            }
            fail(result.statusCode(), result.responseText());
            return;
        }
    }

    private void fail(Integer statusCode, String text) {
        failure.compareAndSet(null, new HeartbeatFailure(statusCode, text));
        cancellation.cancel("heartbeat_failed");
    }

    private HeartbeatRequest currentRequest() {
        HeartbeatRequest body = request.get();
        if (body != null) {
            return body;
        }
        return new HeartbeatRequest(workerName, null, null, null, null, null, null, null);
    }
}
