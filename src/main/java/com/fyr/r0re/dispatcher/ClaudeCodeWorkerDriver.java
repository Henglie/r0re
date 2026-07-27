package com.fyr.r0re.dispatcher;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ClaudeCodeWorkerDriver implements WorkerDriver {
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Override
    public String prepareSession() {
        return UUID.randomUUID().toString();
    }

    @Override
    public List<String> buildHealthcheck(DispatchConfig.WorkerConfig worker) {
        Map<String, String> env = worker.env() == null ? Map.of() : worker.env();
        String payload = "{\"model\":\"" + env.getOrDefault("ANTHROPIC_MODEL", "") +
                "\",\"max_tokens\":10,\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";
        return List.of(
                "curl", "-sS", "--fail", "-o", "/dev/null",
                env.getOrDefault("ANTHROPIC_BASE_URL", "") + "/v1/messages",
                "-H", "Authorization: Bearer " + env.getOrDefault("ANTHROPIC_AUTH_TOKEN", ""),
                "-H", "anthropic-version: " + ANTHROPIC_VERSION,
                "-H", "content-type: application/json",
                "-d", payload
        );
    }

    @Override
    public DriverCommand buildExecute(DispatchConfig.WorkerConfig worker, String prompt, String session) {
        return new DriverCommand(List.of(
                "claude",
                "--session-id", session,
                "--dangerously-skip-permissions",
                "-p",
                "--",
                prompt
        ), session);
    }

    @Override
    public List<String> buildConclude(DispatchConfig.WorkerConfig worker, String prompt, String session) {
        return List.of(
                "claude",
                "-r",
                session,
                "--dangerously-skip-permissions",
                "-p",
                "--",
                prompt
        );
    }
}
