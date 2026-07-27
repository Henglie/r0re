package com.fyr.r0re.dispatcher;

import java.util.List;
import java.util.Map;

public class CodexWorkerDriver implements WorkerDriver {
    @Override
    public List<String> buildStartupHealthcheck(DispatchConfig.WorkerConfig worker) {
        Map<String, String> env = worker.env() == null ? Map.of() : worker.env();
        if (usesChatgptLogin(env)) {
            return List.of(
                    "/bin/sh",
                    "-lc",
                    "command -v npx >/dev/null 2>&1 && test -f \"$CODEX_HOME/auth.json\""
            );
        }
        return WorkerDriver.super.buildStartupHealthcheck(worker);
    }

    @Override
    public List<String> buildHealthcheck(DispatchConfig.WorkerConfig worker) {
        Map<String, String> env = worker.env() == null ? Map.of() : worker.env();
        if (usesChatgptLogin(env)) {
            java.util.ArrayList<String> command = new java.util.ArrayList<>(codexCommandPrefix(env));
            command.add("login");
            command.add("status");
            return command;
        }
        String url = env.getOrDefault("CODEX_BASE_URL", "") + "/responses";
        String payload = "{\"input\":[{\"content\":\"ping\",\"role\":\"user\"}],\"model\":\"" +
                env.getOrDefault("CODEX_MODEL", "") + "\",\"stream\":false}";
        return List.of(
                "curl", "-sS", "--fail", "-o", "/dev/null",
                url,
                "-H", "Authorization: Bearer " + env.getOrDefault("OPENAI_API_KEY", ""),
                "-H", "content-type: application/json",
                "-d", payload
        );
    }

    @Override
    public DriverCommand buildExecute(DispatchConfig.WorkerConfig worker, String prompt, String session) {
        Map<String, String> env = worker.env() == null ? Map.of() : worker.env();
        return new DriverCommand(buildCodexExecCommand(env, prompt, null), session);
    }

    @Override
    public List<String> buildConclude(DispatchConfig.WorkerConfig worker, String prompt, String session) {
        Map<String, String> env = worker.env() == null ? Map.of() : worker.env();
        return buildCodexExecCommand(env, prompt, session);
    }

    private List<String> buildCodexExecCommand(Map<String, String> env, String prompt, String session) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(codexCommandPrefix(env));
        command.add("exec");
        if (session != null && !session.isBlank()) {
            command.add("resume");
            command.add(session);
        }
        command.add("--dangerously-bypass-approvals-and-sandbox");
        command.add("--skip-git-repo-check");
        command.add("--model");
        command.add(env.getOrDefault("CODEX_MODEL", ""));
        if (!usesChatgptLogin(env)) {
            command.add("-c");
            command.add("model_provider=\"cairn\"");
            command.add("-c");
            command.add("model_providers.cairn.name=\"cairn\"");
            command.add("-c");
            command.add("model_providers.cairn.wire_api=\"responses\"");
            command.add("-c");
            command.add("model_reasoning_effort=\"high\"");
            command.add("-c");
            command.add("model_providers.cairn.base_url=\"" + env.getOrDefault("CODEX_BASE_URL", "") + "\"");
            command.add("-c");
            command.add("model_providers.cairn.env_key=\"OPENAI_API_KEY\"");
        }
        command.add("--");
        command.add(prompt);
        return command;
    }

    private List<String> codexCommandPrefix(Map<String, String> env) {
        String npxPackage = env.getOrDefault("CODEX_NPX_PACKAGE", "").trim();
        if (!npxPackage.isBlank()) {
            return List.of("npx", "-y", npxPackage);
        }
        String jsPath = env.getOrDefault("CODEX_JS_PATH", "").trim();
        if (!jsPath.isBlank()) {
            return List.of("node", jsPath);
        }
        return List.of("codex");
    }

    private boolean usesChatgptLogin(Map<String, String> env) {
        String baseUrl = env.getOrDefault("CODEX_BASE_URL", "").trim();
        String apiKey = env.getOrDefault("OPENAI_API_KEY", "").trim();
        return baseUrl.isBlank() && apiKey.isBlank();
    }
}
