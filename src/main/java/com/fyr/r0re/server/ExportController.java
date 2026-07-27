package com.fyr.r0re.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fyr.r0re.protocol.Models.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class ExportController {
    private final R0reService service;
    private final ObjectMapper jsonMapper;
    private final YAMLMapper yamlMapper;

    public ExportController(R0reService service, ObjectMapper jsonMapper) {
        this.service = service;
        this.jsonMapper = jsonMapper;
        this.yamlMapper = new YAMLMapper();
    }

    @GetMapping(value = "/projects/{projectId}/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> exportJson(@PathVariable("projectId") String projectId, @RequestParam(name = "format", defaultValue = "json") String format) throws JsonProcessingException {
        var detail = service.getProject(projectId);
        return switch (format) {
            case "yaml" -> ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(yamlMapper.writeValueAsString(toYamlModel(detail)));
            case "timeline" -> ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(buildTimeline(detail));
            case "json" -> ResponseEntity.ok(toStructuredJson(detail));
            default -> throw HttpErrors.badRequest("format must be json, yaml, or timeline");
        };
    }

    // --- Structured JSON export (with graph nodes/edges/summary) ---

    private Map<String, Object> toStructuredJson(ProjectDetail detail) {
        ProjectMeta meta = detail.project();
        List<Fact> facts = detail.facts();
        List<Intent> intents = detail.intents();
        List<Hint> hints = detail.hints();
        List<VerificationIntent> verificationIntents = service.projectVerificationIntents(meta.id());

        Map<String, String> factsById = new LinkedHashMap<>();
        for (Fact f : facts) {
            factsById.put(f.id(), f.description());
        }

        // summary
        int concludedIntents = 0;
        int openIntents = 0;
        for (Intent i : intents) {
            if (i.concluded_at() != null) concludedIntents++;
            else if (i.worker() == null) openIntents++;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_facts", facts.size());
        summary.put("total_intents", intents.size());
        summary.put("concluded_intents", concludedIntents);
        summary.put("open_intents", openIntents);
        summary.put("total_hints", hints.size());
        summary.put("total_verification_intents", verificationIntents.size());

        // findings (facts excluding origin and goal)
        List<Map<String, String>> findings = new ArrayList<>();
        for (Fact f : facts) {
            if (!"origin".equals(f.id()) && !"goal".equals(f.id())) {
                findings.add(Map.of("id", f.id(), "description", f.description()));
            }
        }

        // graph nodes and edges
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        for (Fact f : facts) {
            nodes.add(Map.of("id", f.id(), "type", "fact", "description", f.description()));
        }
        for (Intent i : intents) {
            String status = i.concluded_at() != null ? "concluded" : (i.worker() != null ? "claimed" : "open");
            Map<String, Object> intentNode = new LinkedHashMap<>();
            intentNode.put("id", i.id());
            intentNode.put("type", "intent");
            intentNode.put("description", i.description());
            intentNode.put("creator", i.creator() != null ? i.creator() : "");
            intentNode.put("worker", i.worker() != null ? i.worker() : "");
            intentNode.put("status", status);
            intentNode.put("from_facts", i.from() != null ? i.from() : List.of());
            intentNode.put("to_fact", i.to() != null ? i.to() : "");
            nodes.add(intentNode);

            if (i.from() != null) {
                for (String src : i.from()) {
                    edges.add(Map.of("source", src, "target", i.id()));
                }
            }
            if (i.to() != null) {
                edges.add(Map.of("source", i.id(), "target", i.to()));
            }
        }

        Map<String, Object> graph = Map.of("nodes", nodes, "edges", edges);

        // hints
        List<Map<String, String>> hintsList = new ArrayList<>();
        for (Hint h : hints) {
            hintsList.add(Map.of("content", h.content(), "creator", h.creator(), "created_at", h.created_at() != null ? h.created_at() : ""));
        }

        List<Map<String, Object>> verificationList = new ArrayList<>();
        for (VerificationIntent v : verificationIntents) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", v.id());
            entry.put("candidate_id", v.candidate_id());
            entry.put("status", v.status());
            entry.put("worker", v.worker());
            entry.put("prompt_group", v.prompt_group());
            entry.put("tool", v.tool());
            entry.put("summary", v.summary());
            entry.put("artifact", v.artifact());
            entry.put("created_at", v.created_at());
            entry.put("updated_at", v.updated_at());
            verificationList.add(entry);
        }

        // project info
        Map<String, Object> projectInfo = new LinkedHashMap<>();
        projectInfo.put("id", meta.id());
        projectInfo.put("title", meta.title());
        projectInfo.put("status", meta.status());
        projectInfo.put("origin", factsById.getOrDefault("origin", ""));
        projectInfo.put("goal", factsById.getOrDefault("goal", ""));
        projectInfo.put("created_at", meta.created_at() != null ? meta.created_at() : "");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", projectInfo);
        result.put("summary", summary);
        result.put("findings", findings);
        result.put("hints", hintsList);
        result.put("verification_intents", verificationList);
        result.put("graph", graph);
        return result;
    }

    // --- YAML export model ---

    private Map<String, Object> toYamlModel(ProjectDetail detail) {
        ProjectMeta meta = detail.project();
        List<Fact> facts = detail.facts();
        List<Intent> intents = detail.intents();
        List<Hint> hints = detail.hints();
        List<VerificationIntent> verificationIntents = service.projectVerificationIntents(meta.id());

        Map<String, String> factsById = new LinkedHashMap<>();
        for (Fact f : facts) factsById.put(f.id(), f.description());

        Map<String, Object> project = new LinkedHashMap<>();
        project.put("title", meta.title());
        project.put("origin", factsById.getOrDefault("origin", ""));
        project.put("goal", factsById.getOrDefault("goal", ""));
        project.put("bootstrap_enabled", meta.bootstrap_enabled());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("project", project);

        if (!hints.isEmpty()) {
            List<Map<String, String>> hintsList = new ArrayList<>();
            for (Hint h : hints) {
                hintsList.add(Map.of("content", h.content(), "creator", h.creator(), "created_at", h.created_at() != null ? h.created_at() : ""));
            }
            data.put("hints", hintsList);
        }

        List<Map<String, String>> factsList = new ArrayList<>();
        List<Map<String, String>> deadEndsList = new ArrayList<>();
        for (Fact f : facts) {
            if ("dead_end".equals(f.kind())) {
                deadEndsList.add(Map.of("id", f.id(), "description", f.description()));
            } else if (Boolean.TRUE.equals(f.verified())) {
                factsList.add(Map.of("id", f.id(), "description", f.description(), "verified", "true"));
            } else {
                factsList.add(Map.of("id", f.id(), "description", f.description()));
            }
        }
        data.put("facts", factsList);
        if (!deadEndsList.isEmpty()) {
            data.put("dead_ends", deadEndsList);
        }

        if (!intents.isEmpty()) {
            List<Map<String, Object>> intentsList = new ArrayList<>();
            for (Intent i : intents) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("from", i.from() != null ? i.from() : List.of());
                entry.put("to", i.to() != null ? i.to() : "");
                entry.put("description", i.description());
                entry.put("creator", i.creator() != null ? i.creator() : "");
                entry.put("worker", i.worker() != null ? i.worker() : "");
                entry.put("created_at", i.created_at() != null ? i.created_at() : "");
                entry.put("concluded_at", i.concluded_at() != null ? i.concluded_at() : "");
                intentsList.add(entry);
            }
            data.put("intents", intentsList);
        }

        if (!verificationIntents.isEmpty()) {
            List<Map<String, Object>> verifierList = new ArrayList<>();
            for (VerificationIntent v : verificationIntents) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("candidate_id", v.candidate_id());
                entry.put("status", v.status());
                entry.put("worker", v.worker());
                entry.put("prompt_group", v.prompt_group());
                entry.put("tool", v.tool());
                entry.put("summary", v.summary());
                entry.put("artifact", v.artifact());
                entry.put("updated_at", v.updated_at());
                verifierList.add(entry);
            }
            data.put("verification_intents", verifierList);
        }

        return data;
    }

    // --- Timeline export ---

    private String buildTimeline(ProjectDetail detail) {
        ProjectMeta meta = detail.project();
        List<Fact> facts = detail.facts();
        List<Intent> intents = detail.intents();
        List<Hint> hints = detail.hints();
        List<VerificationIntent> verificationIntents = service.projectVerificationIntents(meta.id());

        Map<String, String> factsById = new LinkedHashMap<>();
        for (Fact f : facts) factsById.put(f.id(), f.description());

        List<TimelineEvent> events = new ArrayList<>();
        int order = 0;

        // project created
        String originDesc = factsById.getOrDefault("origin", "");
        String goalDesc = factsById.getOrDefault("goal", "");
        String createdTs = meta.created_at() != null ? meta.created_at() : "";
        events.add(new TimelineEvent(createdTs, order++, String.format(
                "[%s] PROJECT CREATED\n  origin: %s\n  goal: %s", createdTs, originDesc, goalDesc)));

        // hints
        for (Hint h : hints) {
            String ts = h.created_at() != null ? h.created_at() : "";
            events.add(new TimelineEvent(ts, order++, String.format(
                    "[%s] HINT by %s\n  %s", ts, h.creator(), h.content())));
        }

        // intents
        for (Intent i : intents) {
            String fromStr = i.from() != null ? String.join(", ", i.from()) : "";
            String createdTs2 = i.created_at() != null ? i.created_at() : "";
            String meta2 = "  from: " + fromStr;
            if (i.worker() != null && i.concluded_at() == null) {
                meta2 += "\n  worker: " + i.worker() + " (in progress)";
            }
            events.add(new TimelineEvent(createdTs2, order++, String.format(
                    "[%s] INTENT DECLARED %s by %s\n%s\n  %s", createdTs2, i.id(), i.creator(), meta2, i.description())));

            if (i.concluded_at() != null && i.to() != null) {
                String actor = i.worker() != null ? i.worker() : i.creator();
                String concludedTs = i.concluded_at();
                if ("goal".equals(i.to())) {
                    events.add(new TimelineEvent(concludedTs, order++, String.format(
                            "[%s] PROJECT COMPLETED by %s\n  via: %s from %s", concludedTs, actor, i.id(), fromStr)));
                } else {
                    String factDesc = factsById.getOrDefault(i.to(), "");
                    events.add(new TimelineEvent(concludedTs, order++, String.format(
                            "[%s] INTENT CONCLUDED %s by %s\n  from: %s\n  produced: %s\n  %s",
                            concludedTs, i.id(), actor, fromStr, i.to(), factDesc)));
                }
            }
        }

        for (VerificationIntent verification : verificationIntents) {
            String ts = verification.updated_at() != null ? verification.updated_at() : (verification.created_at() != null ? verification.created_at() : "");
            String worker = verification.worker() != null ? verification.worker() : "verifier";
            events.add(new TimelineEvent(ts, order++, String.format(
                    "[%s] VERIFICATION INTENT %s by %s\n  candidate: %s\n  status: %s\n  tool: %s\n  %s",
                    ts,
                    verification.id(),
                    worker,
                    verification.candidate_id(),
                    verification.status(),
                    verification.tool() != null ? verification.tool() : "—",
                    verification.summary() != null ? verification.summary() : "no verifier summary")));
        }

        events.sort(Comparator.comparing((TimelineEvent e) -> e.timestamp).thenComparingInt(e -> e.order));

        StringBuilder sb = new StringBuilder();
        for (TimelineEvent e : events) {
            sb.append(e.text).append("\n\n");
        }
        return sb.toString();
    }

    private record TimelineEvent(String timestamp, int order, String text) {
    }
}
