package com.fyr.r0re.dispatcher;

public record RunningTask(
        String projectId,
        String taskType,
        String workerName,
        TaskCancellation cancellation,
        String intentId,
        String phase,
        String costClass,
        String status,
        String startedAt,
        String lastProgressAt,
        int retryCount,
        String sessionId,
        String artifactType,
        Integer artifactCount,
        String lastArtifactAt,
        Integer factCount,
        Integer hintCount,
        Integer openIntentCount,
        String reasonTrigger
) {
}
