package com.fyr.r0re.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Kimi Code CLI worker driver.
 *
 * Execute: {@code kimi -p <prompt> --output-format stream-json}
 * Conclude fallback: {@code kimi -r <session_id> -p <prompt> --output-format stream-json}
 *
 * stream-json emits one JSON object per line on stdout:
 *   {"role":"assistant","content":"..."}                          assistant text
 *   {"role":"assistant","tool_calls":[...]}                       tool invocation (no content)
 *   {"role":"tool","tool_call_id":"...","content":"..."}          tool result
 *   {"role":"meta","type":"session.resume_hint","session_id":...} session id for resume
 *
 * Model/auth come from KIMI_MODEL_* env vars (injected via worker.env), never argv.
 */
public class KimiWorkerDriver implements WorkerDriver {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<String> buildHealthcheck(DispatchConfig.WorkerConfig worker) {
        return List.of("kimi", "--version");
    }

    @Override
    public DriverCommand buildExecute(DispatchConfig.WorkerConfig worker, String prompt, String session) {
        return new DriverCommand(buildKimiCommand(prompt, null), null);
    }

    @Override
    public List<String> buildConclude(DispatchConfig.WorkerConfig worker, String prompt, String session) {
        return buildKimiCommand(prompt, session);
    }

    private List<String> buildKimiCommand(String prompt, String session) {
        List<String> command = new ArrayList<>();
        command.add("kimi");
        if (session != null && !session.isBlank()) {
            command.add("-r");
            command.add(session);
        }
        command.add("-p");
        command.add(prompt);
        command.add("--output-format");
        command.add("stream-json");
        return command;
    }

    @Override
    public String extractSession(String session, String stdout, String stderr) {
        if (stdout == null) {
            return session;
        }
        String found = null;
        for (String line : stdout.split("\\R")) {
            Map<?, ?> obj = parseLine(line);
            if (obj == null) {
                continue;
            }
            Object sessionId = obj.get("session_id");
            if (sessionId instanceof String id && !id.isBlank()) {
                found = id;
            }
        }
        return found != null ? found : session;
    }

    @Override
    public String extractResponseText(String stdout, String stderr) {
        if (stdout == null || stdout.isBlank()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String line : stdout.split("\\R")) {
            Map<?, ?> obj = parseLine(line);
            if (obj == null) {
                continue;
            }
            if ("assistant".equals(obj.get("role")) && obj.get("content") instanceof String content && !content.isBlank()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(content);
            }
        }
        return text.length() > 0 ? text.toString() : stdout;
    }

    private Map<?, ?> parseLine(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
            return null;
        }
        try {
            return MAPPER.readValue(trimmed, Map.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}
