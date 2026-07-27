package com.fyr.r0re.dispatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pi worker driver — mirrors Cairn's PiDriver.
 */
public class PiWorkerDriver implements WorkerDriver {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<String> buildHealthcheck(DispatchConfig.WorkerConfig worker) {
        List<String> argv = new ArrayList<>(basePiArgs(worker));
        argv.add("--no-session");
        argv.add("--no-tools");
        argv.add("-p");
        argv.add("Reply with exactly pong.");
        return wrap(worker, argv, false);
    }

    @Override
    public DriverCommand buildExecute(DispatchConfig.WorkerConfig worker, String prompt, String session) {
        List<String> argv = new ArrayList<>(basePiArgs(worker));
        if (session != null && !session.isBlank()) {
            argv.add("--session");
            argv.add(session);
        }
        argv.add("-p");
        argv.add(prompt);
        return new DriverCommand(wrap(worker, argv, true), session);
    }

    @Override
    public List<String> buildConclude(DispatchConfig.WorkerConfig worker, String prompt, String session) {
        List<String> argv = new ArrayList<>(basePiArgs(worker));
        argv.add("--session");
        argv.add(session);
        argv.add("-p");
        argv.add(prompt);
        return wrap(worker, argv, true);
    }

    @Override
    public String extractSession(String session, String stdout, String stderr) {
        if (session != null && !session.isBlank()) {
            return session;
        }
        for (String line : stdout.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            try {
                JsonNode event = mapper.readTree(trimmed);
                if ("session".equals(event.path("type").asText())) {
                    String id = event.path("id").asText();
                    if (!id.isBlank()) return id;
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    @Override
    public String extractResponseText(String stdout, String stderr) {
        // Exact copy of Cairn's PiDriver.extract_response_text
        JsonNode assistantMessage = null;
        for (String line : stdout.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            try {
                JsonNode event = mapper.readTree(trimmed);
                String type = event.path("type").asText("");
                if ("turn_end".equals(type)) {
                    JsonNode message = event.path("message");
                    if ("assistant".equals(message.path("role").asText())) {
                        assistantMessage = message;
                    }
                } else if ("agent_end".equals(type)) {
                    JsonNode messages = event.path("messages");
                    if (messages.isArray()) {
                        for (int i = messages.size() - 1; i >= 0; i--) {
                            JsonNode msg = messages.get(i);
                            if ("assistant".equals(msg.path("role").asText())) {
                                assistantMessage = msg;
                                break;
                            }
                        }
                    }
                }
            } catch (IOException ignored) {}
        }
        if (assistantMessage == null) return stdout;

        JsonNode content = assistantMessage.path("content");
        if (!content.isArray()) return stdout;

        List<String> parts = new ArrayList<>();
        for (JsonNode item : content) {
            if ("text".equals(item.path("type").asText())) {
                String text = item.path("text").asText("");
                if (!text.isBlank()) parts.add(text);
            }
        }
        String joined = String.join("\n", parts).trim();
        return joined.isEmpty() ? stdout : joined;
    }

    private List<String> basePiArgs(DispatchConfig.WorkerConfig worker) {
        Map<String, String> env = worker.env() == null ? Map.of() : worker.env();
        List<String> argv = new ArrayList<>();
        argv.add("--provider");
        argv.add("cairn");
        argv.add("--model");
        argv.add(env.getOrDefault("PI_MODEL", ""));
        argv.add("--mode");
        argv.add("json");
        argv.add("--session-dir");
        argv.add(sessionDir(worker));
        return argv;
    }

    /**
     * Wrap pi command in a shell script — exact copy of Cairn's _wrap_with_models:
     *
     *   agent_dir="$1"
     *   models_json="$2"
     *   shift 2
     *   mkdir -p "$agent_dir"
     *   mkdir -p "$agent_dir/sessions"
     *   printf "%s" "$models_json" > "$agent_dir/models.json"
     *   exec env PI_CODING_AGENT_DIR="$agent_dir" pi "$@"
     */
    private List<String> wrap(DispatchConfig.WorkerConfig worker, List<String> piArgs, boolean enableTools) {
        List<String> allPiFlags = new ArrayList<>();
        allPiFlags.add("--no-extensions");
        allPiFlags.add("--no-skills");
        allPiFlags.add("--no-prompt-templates");
        allPiFlags.add("--no-themes");
        allPiFlags.add("--no-context-files");
        if (enableTools) {
            allPiFlags.add("--tools");
            allPiFlags.add("read,write,edit,bash,grep,find,ls");
        }
        allPiFlags.addAll(piArgs);

        // Cairn's exact script
        String script = "agent_dir=\"$1\"\n"
                + "models_json=\"$2\"\n"
                + "shift 2\n"
                + "mkdir -p \"$agent_dir\"\n"
                + "mkdir -p \"$agent_dir/sessions\"\n"
                + "printf '%s' \"$models_json\" > \"$agent_dir/models.json\"\n"
                + "exec env PI_CODING_AGENT_DIR=\"$agent_dir\" pi \"$@\"";

        List<String> argv = new ArrayList<>();
        argv.add("/bin/sh");
        argv.add("-lc");
        argv.add(script);
        argv.add("--");
        argv.add(agentDir(worker));
        argv.add(modelsJson(worker));
        argv.addAll(allPiFlags);
        return argv;
    }

    private String modelsJson(DispatchConfig.WorkerConfig worker) {
        Map<String, String> env = worker.env() == null ? Map.of() : worker.env();
        String model = env.getOrDefault("PI_MODEL", "model");
        String context = env.get("PI_MODEL_CONTEXT_WINDOW");
        String contextField = (context == null || context.isBlank()) ? "" : ",\"contextWindow\":" + context;
        String api = normalizeApi(env.getOrDefault("PI_PROVIDER_API", "openai-completions"));
        return "{\"providers\":{\"cairn\":{\"baseUrl\":\"%s\",\"api\":\"%s\",\"apiKey\":\"%s\",\"models\":[{\"id\":\"%s\",\"name\":\"%s\"%s}]}}}".formatted(
                env.getOrDefault("PI_BASE_URL", ""),
                api,
                env.getOrDefault("PI_API_KEY", ""),
                model,
                model,
                contextField
        );
    }

    private String normalizeApi(String api) {
        if (api == null || api.isBlank()) return "openai-completions";
        return switch (api.trim().toLowerCase()) {
            case "anthropic" -> "anthropic-messages";
            case "openai" -> "openai-completions";
            case "openai-responses", "responses" -> "openai-responses";
            case "google" -> "google-generative-ai";
            default -> api.trim();
        };
    }

    private String agentDir(DispatchConfig.WorkerConfig worker) {
        return Path.of("/tmp/cairn-pi", worker.name()).toString();
    }

    private String sessionDir(DispatchConfig.WorkerConfig worker) {
        return Path.of(agentDir(worker), "sessions").toString();
    }
}
