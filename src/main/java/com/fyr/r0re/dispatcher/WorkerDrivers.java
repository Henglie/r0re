package com.fyr.r0re.dispatcher;

public final class WorkerDrivers {
    private WorkerDrivers() {
    }

    public static WorkerDriver forType(String type) {
        return switch (type) {
            case "pi" -> new PiWorkerDriver();
            case "codex" -> new CodexWorkerDriver();
            case "claudecode" -> new ClaudeCodeWorkerDriver();
            case "kimi" -> new KimiWorkerDriver();
            default -> throw new IllegalArgumentException("unsupported worker type: " + type);
        };
    }
}
