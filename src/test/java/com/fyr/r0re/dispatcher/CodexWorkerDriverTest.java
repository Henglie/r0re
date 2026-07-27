package com.fyr.r0re.dispatcher;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexWorkerDriverTest {
    private final CodexWorkerDriver driver = new CodexWorkerDriver();

    @Test
    void usesChatgptLoginHealthcheckWhenApiKeyConfigIsAbsent() {
        DispatchConfig.WorkerConfig worker = new DispatchConfig.WorkerConfig(
                "codex_chatgpt",
                "codex",
                List.of("bootstrap"),
                1,
                0,
                Map.of(
                        "CODEX_MODEL", "gpt-5.4",
                        "CODEX_HOME", "/home/reverser/.codex",
                        "CODEX_NPX_PACKAGE", "@openai/codex"
                )
        );

        assertEquals(
                List.of("/bin/sh", "-lc", "command -v npx >/dev/null 2>&1 && test -f \"$CODEX_HOME/auth.json\""),
                driver.buildStartupHealthcheck(worker)
        );
        assertEquals(List.of("npx", "-y", "@openai/codex", "login", "status"), driver.buildHealthcheck(worker));

        List<String> command = driver.buildExecute(worker, "hello", null).argv();
        assertEquals("npx", command.get(0));
        assertTrue(command.contains("--skip-git-repo-check"));
        assertFalse(command.contains("model_provider=\"cairn\""));
        assertFalse(command.contains("model_providers.cairn.env_key=\"OPENAI_API_KEY\""));
    }

    @Test
    void keepsCustomProviderFlagsWhenApiKeyConfigIsPresent() {
        DispatchConfig.WorkerConfig worker = new DispatchConfig.WorkerConfig(
                "codex_openai",
                "codex",
                List.of("bootstrap"),
                1,
                0,
                Map.of(
                        "CODEX_MODEL", "gpt-5.4",
                        "CODEX_BASE_URL", "https://api.openai.com/v1",
                        "OPENAI_API_KEY", "test-api-key"
                )
        );

        List<String> healthcheck = driver.buildHealthcheck(worker);
        assertEquals("curl", healthcheck.get(0));

        List<String> command = driver.buildExecute(worker, "hello", null).argv();
        assertTrue(command.contains("model_provider=\"cairn\""));
        assertTrue(command.contains("model_providers.cairn.env_key=\"OPENAI_API_KEY\""));
    }
}
