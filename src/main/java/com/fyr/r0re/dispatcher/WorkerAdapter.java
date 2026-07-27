package com.fyr.r0re.dispatcher;

import com.fyr.r0re.protocol.Models.ProjectDetail;

public interface WorkerAdapter {
    default void startupHealthcheck(DispatchConfig.WorkerConfig worker, int timeoutSeconds) {
    }

    default void taskHealthcheck(String projectId, DispatchConfig.WorkerConfig worker, int timeoutSeconds) {
    }

    WorkerResult bootstrap(ProjectDetail project, DispatchConfig.WorkerConfig worker, DispatchConfig.TaskTimeout taskConfig, TaskCancellation cancellation);

    WorkerResult reason(ProjectDetail project, DispatchConfig.WorkerConfig worker, DispatchConfig.ReasonTask taskConfig, TaskCancellation cancellation);

    WorkerResult explore(ProjectDetail project, com.fyr.r0re.protocol.Models.Intent intent, DispatchConfig.WorkerConfig worker, DispatchConfig.TaskTimeout taskConfig, TaskCancellation cancellation);

    /** Swarm race-phase task; swarmWorkerName is the unique board identity (worker.name() + suffix). */
    default WorkerResult race(ProjectDetail project, DispatchConfig.WorkerConfig worker, String swarmWorkerName, DispatchConfig.TaskTimeout taskConfig, TaskCancellation cancellation) {
        return null;
    }

    /** Swarm explore task; the worker claims an intent itself via the blackboard. Null result means board-only. */
    default WorkerResult swarmExplore(ProjectDetail project, DispatchConfig.WorkerConfig worker, String swarmWorkerName, DispatchConfig.TaskTimeout taskConfig, TaskCancellation cancellation) {
        return null;
    }
}
