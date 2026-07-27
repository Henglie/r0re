package com.fyr.r0re.dispatcher;

import java.util.List;

public record WorkerSelection(
        DispatchConfig.WorkerConfig worker,
        List<String> blockedBusy,
        List<String> blockedUnhealthy,
        List<String> blockedRejected,
        List<String> blockedTaskType
) {
}
