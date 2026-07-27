package com.fyr.r0re.dispatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fyr.r0re.protocol.Models.Intent;
import com.fyr.r0re.protocol.Models.ProjectDetail;
import java.util.ArrayList;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.core.io.ClassPathResource;

public class PromptFactory {
    private static final List<String> ANALYSIS_SEARCH_ROOTS = List.of("jadx_out", "jadx_real", "smali_real", "apktool_out");
    private static final List<String> SEARCH_FILE_SUFFIXES = List.of(".java", ".kt", ".smali", ".xml", ".txt", ".md");
    private static final Pattern JAVA_NATIVE_METHOD = Pattern.compile("\\bnative\\s+([\\w.$\\[\\]/<>]+)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(([^)]*)\\)");
    private static final Pattern SMALI_NATIVE_METHOD = Pattern.compile("^\\.method\\s+.*\\bnative\\b.*\\s([A-Za-z_$][A-Za-z0-9_$]*)\\(([^)]*)\\)(.+)$");
    private static final Pattern LOAD_LIBRARY_CALL = Pattern.compile("System\\.loadLibrary\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern LOAD_CALL = Pattern.compile("System\\.load\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_.$/-]{2,}\\b");
    private static final int ANALYSIS_WALK_DEPTH = 6;
    private static final int ANALYSIS_FILE_LIMIT = 2000;
    private static final int SEARCH_FILE_LIMIT = 400;
    private final YAMLMapper yaml = new YAMLMapper();
    private final ObjectMapper json = new ObjectMapper();
    private final String group;

    public PromptFactory(String group) {
        this.group = group == null || group.isBlank() ? "default" : group;
    }

    public String bootstrap(ProjectDetail project) {
        return render(
                "bootstrap.md",
                Map.of(
                        "origin", fact(project, "origin"),
                        "goal", fact(project, "goal"),
                        "hints", json(hints(project))
                )
        );
    }

    public String reason(ProjectDetail project, int maxIntents) {
        return reason(project, maxIntents, compactReasonGraph(project));
    }

    public String reason(ProjectDetail project, int maxIntents, String graphYaml) {
        return renderPreferred(
                reasonTemplateCandidates(project),
                Map.of(
                        "graph_yaml", graphYaml,
                        "fact_ids", json(validFactIds(project)),
                        "open_intents", json(openIntents(project)),
                        "max_intents", String.valueOf(maxIntents)
                )
        );
    }

    public String explore(ProjectDetail project, Intent intent) {
        return explore(project, intent, compactExploreGraph(project, intent));
    }

    public String explore(ProjectDetail project, Intent intent, String graphYaml) {
        return renderPreferred(
                exploreTemplateCandidates(intent, false),
                Map.of(
                        "graph_yaml", graphYaml,
                        "intent_id", intent.id(),
                        "intent_description", intent.description()
                )
        );
    }

    public String race(ProjectDetail project, String graphReference) {
        return render(
                "race.md",
                Map.of(
                        "origin", fact(project, "origin"),
                        "goal", fact(project, "goal"),
                        "hints", json(hints(project)),
                        "graph_yaml", graphOrDefault(project, graphReference)
                )
        );
    }

    public String swarmExplore(ProjectDetail project, String graphReference) {
        return render(
                "swarm_explore.md",
                Map.of(
                        "origin", fact(project, "origin"),
                        "goal", fact(project, "goal"),
                        "hints", json(hints(project)),
                        "graph_yaml", graphOrDefault(project, graphReference)
                )
        );
    }

    public String bootstrapConclude(ProjectDetail project) {
        return render(
                "bootstrap_conclude.md",
                Map.of(
                        "origin", fact(project, "origin"),
                        "goal", fact(project, "goal"),
                        "hints", json(hints(project))
                )
        );
    }

    public String exploreConclude(ProjectDetail project, Intent intent) {
        return exploreConclude(project, intent, compactExploreGraph(project, intent));
    }

    public String exploreConclude(ProjectDetail project, Intent intent, String graphYaml) {
        return renderPreferred(
                exploreTemplateCandidates(intent, true),
                Map.of(
                        "graph_yaml", graphYaml,
                        "intent_id", intent.id(),
                        "intent_description", intent.description()
                )
        );
    }

    public String exportGraphYaml(ProjectDetail project) {
        try {
            return yaml.writeValueAsString(exportGraphModel(project));
        } catch (JsonProcessingException exc) {
            return graph(project);
        }
    }

    public String exportReasonGraphYaml(ProjectDetail project) {
        return exportGraphYaml(project);
    }

    public String exportExploreGraphYaml(ProjectDetail project, Intent intent) {
        return exportGraphYaml(project);
    }

    private String graph(ProjectDetail project) {
        try {
            return yaml.writeValueAsString(project);
        } catch (JsonProcessingException exc) {
            return project.toString();
        }
    }

    private String compactReasonGraph(ProjectDetail project) {
        return compactGraphYaml(project, null, 14, 10, 6);
    }

    private String graphOrDefault(ProjectDetail project, String graphReference) {
        if (graphReference != null && !graphReference.isBlank()) {
            return graphReference;
        }
        return compactReasonGraph(project);
    }

    private String compactExploreGraph(ProjectDetail project, Intent intent) {
        return compactGraphYaml(project, intent, 10, 8, 4);
    }

    private String compactGraphYaml(ProjectDetail project, Intent focusIntent, int maxFacts, int maxIntents, int maxHints) {
        try {
            return yaml.writeValueAsString(compactGraphModel(project, focusIntent, maxFacts, maxIntents, maxHints));
        } catch (JsonProcessingException exc) {
            return graph(project);
        }
    }

    private String render(String name, Map<String, String> replacements) {
        String text = load(group, name);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return text;
    }

    private String renderPreferred(List<String> names, Map<String, String> replacements) {
        String text = null;
        for (String name : names) {
            text = tryLoad(group, name);
            if (text != null) {
                break;
            }
        }
        if (text == null) {
            throw new IllegalStateException("failed to load prompt variants " + names);
        }
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return text;
    }

    private String load(String group, String name) {
        String text = tryLoad(group, name);
        if (text != null) {
            return text;
        }
        throw new IllegalStateException("failed to load prompt prompts/" + group + "/" + name);
    }

    private String tryLoad(String group, String name) {
        String path = "prompts/" + group + "/" + name;
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists() && !"default".equals(group)) {
            resource = new ClassPathResource("prompts/default/" + name);
        }
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exc) {
            throw new IllegalStateException("failed to load prompt " + path, exc);
        }
    }

    private String fact(ProjectDetail project, String id) {
        return project.facts().stream()
                .filter(fact -> id.equals(fact.id()))
                .map(fact -> fact.description())
                .findFirst()
                .orElse("");
    }

    private String json(Object value) {
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException exc) {
            return String.valueOf(value);
        }
    }

    private List<Map<String, String>> hints(ProjectDetail project) {
        return project.hints().stream()
                .map(hint -> Map.of(
                        "id", hint.id(),
                        "content", hint.content(),
                        "creator", hint.creator(),
                        "created_at", hint.created_at() == null ? "" : hint.created_at()
                ))
                .toList();
    }

    private List<Map<String, String>> recentHints(ProjectDetail project, int limit) {
        List<Map<String, String>> hints = new ArrayList<>(hints(project));
        if (hints.size() <= limit) {
            return hints;
        }
        return hints.subList(Math.max(0, hints.size() - limit), hints.size());
    }

    private List<String> validFactIds(ProjectDetail project) {
        return project.facts().stream()
                .map(fact -> fact.id())
                .filter(id -> !"goal".equals(id))
                .toList();
    }

    private List<Map<String, Object>> openIntents(ProjectDetail project) {
        return project.intents().stream()
                .filter(intent -> intent.concluded_at() == null)
                .map(intent -> Map.of(
                        "id", intent.id(),
                        "from", intent.from(),
                        "description", intent.description(),
                        "worker", intent.worker() == null ? "" : intent.worker()
                ))
                .toList();
    }

    private Map<String, Object> exportGraphModel(ProjectDetail project) {
        return graphModel(project, project.facts(), project.intents(), hints(project), Map.of("scope", "full"));
    }

    private Map<String, Object> compactGraphModel(ProjectDetail project, Intent focusIntent, int maxFacts, int maxIntents, int maxHints) {
        Set<String> selectedFactIds = new HashSet<>();
        selectedFactIds.add("origin");
        selectedFactIds.add("goal");
        if (focusIntent != null && focusIntent.from() != null) {
            selectedFactIds.addAll(focusIntent.from());
        }
        for (Intent intent : project.intents()) {
            if (intent.concluded_at() == null) {
                if (intent.from() != null) {
                    selectedFactIds.addAll(intent.from());
                }
                if (intent.to() != null && !intent.to().isBlank()) {
                    selectedFactIds.add(intent.to());
                }
            }
        }

        List<com.fyr.r0re.protocol.Models.Fact> prioritizedFacts = new ArrayList<>();
        List<com.fyr.r0re.protocol.Models.Fact> recentFacts = project.facts().stream()
                .filter(fact -> !"origin".equals(fact.id()) && !"goal".equals(fact.id()))
                .sorted(Comparator.comparing(fact -> fact.id()))
                .toList();
        for (var fact : recentFacts) {
            if (selectedFactIds.contains(fact.id())) {
                prioritizedFacts.add(fact);
            }
        }
        for (int i = Math.max(0, recentFacts.size() - maxFacts); i < recentFacts.size(); i++) {
            var fact = recentFacts.get(i);
            if (prioritizedFacts.stream().noneMatch(existing -> existing.id().equals(fact.id()))) {
                prioritizedFacts.add(fact);
            }
        }

        List<com.fyr.r0re.protocol.Models.Fact> compactFacts = new ArrayList<>();
        project.facts().stream()
                .filter(fact -> "origin".equals(fact.id()) || "goal".equals(fact.id()))
                .forEach(compactFacts::add);
        compactFacts.addAll(prioritizedFacts.stream().limit(maxFacts).toList());
        project.facts().stream()
                .filter(fact -> "dead_end".equals(fact.kind()))
                .filter(fact -> compactFacts.stream().noneMatch(existing -> existing.id().equals(fact.id())))
                .forEach(compactFacts::add);

        List<Intent> compactIntents = new ArrayList<>();
        if (focusIntent != null) {
            compactIntents.add(focusIntent);
        }
        project.intents().stream()
                .filter(intent -> intent.concluded_at() == null)
                .filter(intent -> focusIntent == null || !intent.id().equals(focusIntent.id()))
                .forEach(compactIntents::add);
        List<Intent> recentConcluded = project.intents().stream()
                .filter(intent -> intent.concluded_at() != null)
                .sorted(Comparator.comparing(intent -> intent.concluded_at() == null ? "" : intent.concluded_at()))
                .toList();
        for (int i = Math.max(0, recentConcluded.size() - maxIntents); i < recentConcluded.size(); i++) {
            Intent intent = recentConcluded.get(i);
            if (compactIntents.stream().noneMatch(existing -> existing.id().equals(intent.id()))) {
                compactIntents.add(intent);
            }
        }
        if (compactIntents.size() > maxIntents) {
            compactIntents = new ArrayList<>(compactIntents.subList(0, maxIntents));
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("scope", focusIntent == null ? "reason_compact" : "explore_compact");
        meta.put("analysis_phase", focusIntent == null ? "reasoning" : classifyIntentPhase(focusIntent));
        meta.put("focus_intent_id", focusIntent == null ? "" : focusIntent.id());
        meta.put("facts_in_project", project.facts().size());
        meta.put("facts_in_context", compactFacts.size());
        meta.put("intents_in_project", project.intents().size());
        meta.put("intents_in_context", compactIntents.size());
        meta.put("hints_in_project", project.hints().size());
        meta.put("hints_in_context", Math.min(project.hints().size(), maxHints));

        return graphModel(project, compactFacts, compactIntents, recentHints(project, maxHints), meta);
    }

    private Map<String, Object> graphModel(
            ProjectDetail project,
            List<com.fyr.r0re.protocol.Models.Fact> factsInput,
            List<Intent> intentsInput,
            List<Map<String, String>> hintsInput,
            Map<String, Object> meta
    ) {
        Map<String, String> factsById = new LinkedHashMap<>();
        for (var fact : factsInput) {
            factsById.put(fact.id(), fact.description());
        }

        Map<String, Object> projectNode = new LinkedHashMap<>();
        projectNode.put("title", project.project().title());
        projectNode.put("origin", factsById.getOrDefault("origin", ""));
        projectNode.put("goal", factsById.getOrDefault("goal", ""));
        projectNode.put("bootstrap_enabled", project.project().bootstrap_enabled());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("project", projectNode);
        if (!meta.isEmpty()) {
            data.put("context_meta", meta);
        }
        Map<String, List<String>> phaseSummaries = phaseSummaries(factsInput);
        if (!phaseSummaries.isEmpty()) {
            data.put("phase_summaries", phaseSummaries);
        }
        Map<String, Object> analysisArtifacts = analysisArtifactsSummary(project, factsInput, intentsInput, meta);
        if (!analysisArtifacts.isEmpty()) {
            data.put("analysis_artifacts", analysisArtifacts);
        }

        if (!hintsInput.isEmpty()) {
            data.put("hints", new ArrayList<>(hintsInput));
        }

        List<Map<String, Object>> facts = new ArrayList<>();
        factsInput.stream()
                .filter(fact -> !"dead_end".equals(fact.kind()))
                .forEach(fact -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", fact.id());
                    entry.put("description", fact.description());
                    if (Boolean.TRUE.equals(fact.verified())) {
                        entry.put("verified", true);
                    }
                    facts.add(entry);
                });
        data.put("facts", facts);

        List<Map<String, String>> deadEnds = new ArrayList<>();
        factsInput.stream()
                .filter(fact -> "dead_end".equals(fact.kind()))
                .forEach(fact -> deadEnds.add(Map.of(
                        "id", fact.id(),
                        "description", fact.description()
                )));
        if (!deadEnds.isEmpty()) {
            data.put("dead_ends", deadEnds);
        }

        if (!intentsInput.isEmpty()) {
            List<Map<String, Object>> intents = new ArrayList<>();
            intentsInput.forEach(intent -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", intent.id());
                entry.put("from", intent.from() == null ? List.of() : intent.from());
                entry.put("to", intent.to() == null ? "" : intent.to());
                entry.put("description", intent.description());
                entry.put("creator", intent.creator() == null ? "" : intent.creator());
                entry.put("worker", intent.worker() == null ? "" : intent.worker());
                entry.put("created_at", intent.created_at() == null ? "" : intent.created_at());
                entry.put("concluded_at", intent.concluded_at() == null ? "" : intent.concluded_at());
                intents.add(entry);
            });
            data.put("intents", intents);
        }
        return data;
    }

    private List<String> exploreTemplateCandidates(Intent intent, boolean conclude) {
        String phase = classifyIntentPhase(intent);
        List<String> names = new ArrayList<>();
        if ("native".equals(phase)) {
            names.addAll(nativeExploreTemplateCandidates(intent, conclude));
        }
        if (conclude) {
            names.add("explore_" + phase + "_conclude.md");
            names.add("explore_conclude.md");
        } else {
            names.add("explore_" + phase + ".md");
            names.add("explore.md");
        }
        return names;
    }

    private List<String> nativeExploreTemplateCandidates(Intent intent, boolean conclude) {
        String suffix = conclude ? "_conclude.md" : ".md";
        String text = intent == null || intent.description() == null ? "" : intent.description().toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        if (containsAny(text, "upx", "packed", "packer", "unpack", "shell", "godot", "gdextension", "gdc", "nrv2b", "unicorn", ".sparsepck")) {
            names.add("explore_native_packed" + suffix);
        }
        if (containsAny(text, "smc", "self-modifying", "self modifying", "decrypted payload", "mprotect", "cacheflush")) {
            names.add("explore_native_smc" + suffix);
        }
        if (containsAny(text, "jni", "java_", "bridge", "registernatives", "loadlibrary")) {
            names.add("explore_native_jni" + suffix);
        }
        if (containsAny(text, "constant", "operand", "rodata", ".data", "memcmp", "strcmp", "xor key", "table", "hash", "digest")) {
            names.add("explore_native_constants" + suffix);
        }
        if (containsAny(text, "flag", "candidate", "verify", "validation", "compare", "expected input")) {
            names.add("explore_native_verify" + suffix);
        }
        return names;
    }

    private List<String> reasonTemplateCandidates(ProjectDetail project) {
        return List.of("reason.md");
    }

    private String classifyReasonPhase(ProjectDetail project) {
        StringBuilder text = new StringBuilder();
        text.append(fact(project, "origin")).append(' ');
        text.append(fact(project, "goal")).append(' ');
        for (var fact : project.facts()) {
            text.append(fact.description()).append(' ');
        }
        for (var hint : project.hints()) {
            text.append(hint.content()).append(' ');
        }
        for (Intent intent : project.intents()) {
            text.append(intent.description()).append(' ');
        }
        String normalized = text.toString().toLowerCase();
        if (containsAny(normalized, "jni", ".so", "native", "zygisk", "root", "frida", "offset", "symbol", "elf", "lib")) {
            return "native";
        }
        if (containsAny(normalized, "manifest", "permission", "exported", "activity", "service", "receiver", "provider", "deep link")) {
            return "manifest";
        }
        return "java";
    }

    private String classifyIntentPhase(Intent intent) {
        String text = ((intent == null ? "" : intent.description()) + " " + String.join(" ", intent == null || intent.from() == null ? List.of() : intent.from())).toLowerCase();
        if (containsAny(text, "jni", ".so", "native", "offset", "lib", "arm64", "symbol", "elf")) {
            return "native";
        }
        if (containsAny(text, "manifest", "permission", "exported", "activity", "service", "receiver", "provider", "deep link")) {
            return "manifest";
        }
        if (containsAny(text, "resource", "string", "asset", "xml", "res/", "layout", "config")) {
            return "resource";
        }
        return "java";
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, List<String>> phaseSummaries(List<com.fyr.r0re.protocol.Models.Fact> factsInput) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (var fact : factsInput) {
            if ("origin".equals(fact.id()) || "goal".equals(fact.id())) {
                continue;
            }
            String bucket = classifyFactPhase(fact.description());
            grouped.computeIfAbsent(bucket, ignored -> new ArrayList<>());
            List<String> items = grouped.get(bucket);
            if (items.size() < 4) {
                items.add(fact.id() + ": " + trimSummary(fact.description(), 180));
            }
        }
        return grouped;
    }

    private String classifyFactPhase(String description) {
        String text = description == null ? "" : description.toLowerCase();
        if (containsAny(text, "manifest", "permission", "exported", "activity", "service", "receiver", "provider")) {
            return "manifest_summary";
        }
        if (containsAny(text, "jni", ".so", "native", "offset", "symbol", "arm64", "elf")) {
            return "native_summary";
        }
        if (containsAny(text, "string", "asset", "resource", "xml", "res/")) {
            return "resource_summary";
        }
        if (containsAny(text, "http", "https", "okhttp", "retrofit", "request", "tls", "pinning")) {
            return "network_summary";
        }
        if (containsAny(text, "sharedpreferences", "sqlite", "database", "contentprovider", "keystore", "storage")) {
            return "storage_summary";
        }
        return "java_summary";
    }

    private String trimSummary(String value, int max) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max) + "...";
    }

    private Map<String, Object> analysisArtifactsSummary(ProjectDetail project,
                                                         List<com.fyr.r0re.protocol.Models.Fact> factsInput,
                                                         List<Intent> intentsInput,
                                                         Map<String, Object> meta) {
        String projectId = project == null || project.project() == null ? null : project.project().id();
        if (projectId == null || projectId.isBlank()) {
            return Map.of();
        }
        Path analysisDir = Path.of(System.getProperty("user.dir")).resolve("output").resolve("live-analysis").resolve(projectId);
        if (!Files.isDirectory(analysisDir)) {
            return Map.of();
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        List<Path> allFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(analysisDir, ANALYSIS_WALK_DEPTH)) {
            allFiles = stream.filter(Files::isRegularFile)
                    .filter(this::shouldIndexArtifact)
                    .limit(ANALYSIS_FILE_LIMIT)
                    .toList();
        } catch (IOException ignored) {
            return Map.of();
        }
        for (Path file : allFiles) {
            String type = artifactType(analysisDir.relativize(file));
            counts.put(type, counts.getOrDefault(type, 0) + 1);
            Map<String, Object> current = latest.get(type);
            if (current == null || compareArtifactTime(file, current) > 0) {
                latest.put(type, artifactSummary(analysisDir, file, type));
            }
        }
        if (!counts.isEmpty()) {
            summary.put("artifact_counts", counts);
        }
        if (!latest.isEmpty()) {
            summary.put("latest_artifacts", latest);
        }

        Path workspaceSnapshot = analysisDir.resolve("workspace-snapshot");
        if (Files.isDirectory(workspaceSnapshot)) {
            List<Path> roots = locateSearchRoots(workspaceSnapshot);
            if (!roots.isEmpty()) {
                summary.put("search_roots", roots.stream()
                        .map(root -> workspaceSnapshot.relativize(root).toString().replace('\\', '/'))
                        .toList());
            }
            Map<String, List<String>> librariesByClass = new LinkedHashMap<>();
            for (Path root : roots) {
                collectLibraryLoads(root, workspaceSnapshot, librariesByClass);
            }
            List<Map<String, Object>> jniEntries = new ArrayList<>();
            for (Path root : roots) {
                if (jniEntries.size() >= 6) {
                    break;
                }
                collectJniEntries(root, workspaceSnapshot, librariesByClass, jniEntries, 6);
            }
            if (!jniEntries.isEmpty()) {
                summary.put("jni_method_map", jniEntries);
            }
            List<Map<String, Object>> jadxSearch = buildJadxSearchSummary(workspaceSnapshot, roots, factsInput, intentsInput, meta, jniEntries);
            if (!jadxSearch.isEmpty()) {
                summary.put("jadx_search", jadxSearch);
            }
        }
        return summary;
    }

    private List<Map<String, Object>> buildJadxSearchSummary(Path workspaceSnapshot,
                                                             List<Path> roots,
                                                             List<com.fyr.r0re.protocol.Models.Fact> factsInput,
                                                             List<Intent> intentsInput,
                                                             Map<String, Object> meta,
                                                             List<Map<String, Object>> jniEntries) {
        List<String> queries = deriveJadxQueries(factsInput, intentsInput, meta, jniEntries);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String query : queries) {
            if (results.size() >= 4) {
                break;
            }
            List<Map<String, Object>> matches = searchWorkspaceSnapshot(workspaceSnapshot, roots, query, 2);
            if (matches.isEmpty()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("query", query);
            entry.put("matches", matches);
            results.add(entry);
        }
        return results;
    }

    private List<String> deriveJadxQueries(List<com.fyr.r0re.protocol.Models.Fact> factsInput,
                                           List<Intent> intentsInput,
                                           Map<String, Object> meta,
                                           List<Map<String, Object>> jniEntries) {
        LinkedHashMap<String, String> queries = new LinkedHashMap<>();
        String phase = String.valueOf(meta.getOrDefault("analysis_phase", ""));
        if ("native".equals(phase)) {
            queries.put("native", "native");
            queries.put("loadLibrary", "loadLibrary");
        } else if ("manifest".equals(phase)) {
            queries.put("android.permission", "android.permission");
            queries.put("exported", "exported");
        } else {
            queries.put("native", "native");
            queries.put("TODO", "TODO");
        }

        for (Map<String, Object> jniEntry : jniEntries) {
            addQueryIfUseful(queries, stringValue(jniEntry.get("method_name")));
            addQueryIfUseful(queries, stringValue(jniEntry.get("probable_symbol")));
            Object libraries = jniEntry.get("libraries");
            if (libraries instanceof List<?> libs) {
                for (Object library : libs) {
                    addQueryIfUseful(queries, stringValue(library));
                }
            }
            if (queries.size() >= 8) {
                break;
            }
        }

        for (Intent intent : intentsInput) {
            addQueriesFromText(queries, intent.description(), 2);
            if (queries.size() >= 8) {
                break;
            }
        }
        for (com.fyr.r0re.protocol.Models.Fact fact : factsInput) {
            addQueriesFromText(queries, fact.description(), 2);
            if (queries.size() >= 8) {
                break;
            }
        }
        return new ArrayList<>(queries.values()).subList(0, Math.min(4, queries.size()));
    }

    private void addQueriesFromText(Map<String, String> queries, String text, int limit) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        int added = 0;
        while (matcher.find() && added < limit && queries.size() < 8) {
            String token = matcher.group();
            if (addQueryIfUseful(queries, token)) {
                added++;
            }
        }
    }

    private boolean addQueryIfUseful(Map<String, String> queries, String raw) {
        if (raw == null) {
            return false;
        }
        String query = raw.trim();
        if (query.length() < 3 || query.length() > 48) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.startsWith("f0") || lower.startsWith("i0") || lower.startsWith("h0")) {
            return false;
        }
        if (lower.contains("/") && !lower.contains("res/")) {
            return false;
        }
        if (!lower.contains(".") && lower.chars().filter(Character::isLetter).count() < 3) {
            return false;
        }
        if (Set.of("analysis", "android", "project", "worker", "reason", "explore", "intent", "goal", "origin", "class", "method").contains(lower)) {
            return false;
        }
        if (!queries.containsKey(lower)) {
            queries.put(lower, query);
            return true;
        }
        return false;
    }

    private List<Map<String, Object>> searchWorkspaceSnapshot(Path workspaceSnapshot, List<Path> roots, String query, int limit) {
        List<Map<String, Object>> matches = new ArrayList<>();
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        for (Path root : roots) {
            if (matches.size() >= limit) {
                break;
            }
            try (Stream<Path> stream = Files.walk(root, ANALYSIS_WALK_DEPTH)) {
                for (Path file : stream.filter(Files::isRegularFile)
                        .filter(this::searchableFile)
                        .sorted()
                        .limit(SEARCH_FILE_LIMIT)
                        .toList()) {
                    if (matches.size() >= limit) {
                        break;
                    }
                    List<String> lines;
                    try {
                        lines = Files.readAllLines(file);
                    } catch (IOException ignored) {
                        continue;
                    }
                    for (int i = 0; i < lines.size() && matches.size() < limit; i++) {
                        String line = lines.get(i);
                        if (!line.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                            continue;
                        }
                        Map<String, Object> match = new LinkedHashMap<>();
                        match.put("path", workspaceSnapshot.relativize(file).toString().replace('\\', '/'));
                        match.put("line", i + 1);
                        match.put("snippet", trimSummary(line, 160));
                        matches.add(match);
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return matches;
    }

    private boolean searchableFile(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String suffix : SEARCH_FILE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<Path> locateSearchRoots(Path workspaceSnapshot) {
        List<Path> roots = new ArrayList<>();
        for (String name : ANALYSIS_SEARCH_ROOTS) {
            try (Stream<Path> stream = Files.walk(workspaceSnapshot, 4)) {
                stream.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().equals(name))
                        .sorted()
                        .forEach(path -> {
                            if (!roots.contains(path)) {
                                roots.add(path);
                            }
                        });
            } catch (IOException ignored) {
            }
        }
        return roots;
    }

    private void collectLibraryLoads(Path root, Path workspaceSnapshot, Map<String, List<String>> librariesByClass) {
        try (Stream<Path> stream = Files.walk(root, ANALYSIS_WALK_DEPTH)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return lower.endsWith(".java") || lower.endsWith(".kt");
                    })
                    .limit(SEARCH_FILE_LIMIT)
                    .toList()) {
                String content;
                try {
                    content = Files.readString(file);
                } catch (IOException ignored) {
                    continue;
                }
                String className = classNameFromPath(workspaceSnapshot, file);
                Matcher loadLibrary = LOAD_LIBRARY_CALL.matcher(content);
                while (loadLibrary.find()) {
                    librariesByClass.computeIfAbsent(className, ignored -> new ArrayList<>());
                    List<String> libs = librariesByClass.get(className);
                    if (!libs.contains(loadLibrary.group(1))) {
                        libs.add(loadLibrary.group(1));
                    }
                }
                Matcher loadCall = LOAD_CALL.matcher(content);
                while (loadCall.find()) {
                    librariesByClass.computeIfAbsent(className, ignored -> new ArrayList<>());
                    List<String> libs = librariesByClass.get(className);
                    if (!libs.contains(loadCall.group(1))) {
                        libs.add(loadCall.group(1));
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void collectJniEntries(Path root, Path workspaceSnapshot, Map<String, List<String>> librariesByClass,
                                   List<Map<String, Object>> entries, int limit) {
        try (Stream<Path> stream = Files.walk(root, ANALYSIS_WALK_DEPTH)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".smali");
                    })
                    .sorted()
                    .limit(SEARCH_FILE_LIMIT)
                    .toList()) {
                if (entries.size() >= limit) {
                    return;
                }
                List<String> lines;
                try {
                    lines = Files.readAllLines(file);
                } catch (IOException ignored) {
                    continue;
                }
                String sourceKind = sourceKind(file);
                String className = classNameFromPath(workspaceSnapshot, file);
                List<String> libraries = librariesByClass.getOrDefault(className, List.of());
                for (int i = 0; i < lines.size() && entries.size() < limit; i++) {
                    String line = lines.get(i);
                    Matcher javaMatcher = JAVA_NATIVE_METHOD.matcher(line);
                    if (javaMatcher.find()) {
                        entries.add(jniEntry(workspaceSnapshot, file, className, javaMatcher.group(2), javaMatcher.group(3), i + 1, sourceKind, libraries));
                        continue;
                    }
                    Matcher smaliMatcher = SMALI_NATIVE_METHOD.matcher(line.trim());
                    if (smaliMatcher.find()) {
                        entries.add(jniEntry(workspaceSnapshot, file, className, smaliMatcher.group(1), smaliMatcher.group(2), i + 1, sourceKind, libraries));
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private Map<String, Object> jniEntry(Path workspaceSnapshot, Path file, String className, String methodName, String signature,
                                         int line, String sourceKind, List<String> libraries) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("class_name", className);
        entry.put("method_name", methodName);
        entry.put("signature", signature == null ? "" : signature.trim());
        entry.put("source_path", workspaceSnapshot.relativize(file).toString().replace('\\', '/'));
        entry.put("line", line);
        entry.put("source_kind", sourceKind);
        entry.put("probable_symbol", probableJniSymbol(className, methodName));
        if (!libraries.isEmpty()) {
            entry.put("libraries", libraries);
        }
        return entry;
    }

    private String probableJniSymbol(String className, String methodName) {
        String normalizedClass = className.replace('.', '_').replace('$', '_');
        return "Java_" + normalizedClass + "_" + methodName;
    }

    private String classNameFromPath(Path workspaceSnapshot, Path file) {
        String relative = workspaceSnapshot.relativize(file).toString().replace('\\', '/');
        int marker = relative.indexOf('/');
        if (marker >= 0) {
            relative = relative.substring(marker + 1);
        }
        if (relative.endsWith(".java")) {
            relative = relative.substring(0, relative.length() - 5);
        } else if (relative.endsWith(".kt")) {
            relative = relative.substring(0, relative.length() - 3);
        } else if (relative.endsWith(".smali")) {
            relative = relative.substring(0, relative.length() - 6);
        }
        return relative.replace('/', '.');
    }

    private String sourceKind(Path file) {
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".smali")) {
            return "smali";
        }
        if (lower.endsWith(".kt")) {
            return "kotlin";
        }
        return "java";
    }

    private String artifactType(Path relativePath) {
        String relative = relativePath.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = relativePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (relative.startsWith("llm-traces/") || relative.contains("/llm-traces/")) return "trace";
        if (relative.startsWith("llm-counts/") || relative.contains("/llm-counts/")) return "count";
        if (relative.startsWith("workspace-snapshot/") || relative.contains("/workspace-snapshot/")) return "snapshot";
        if (name.endsWith(".md")) return "report";
        if (name.endsWith(".json") || name.endsWith(".jsonl") || name.endsWith(".yaml") || name.endsWith(".yml")) return "metadata";
        if (name.endsWith(".txt")) return "text";
        if (name.endsWith(".bin")) return "binary";
        if (name.endsWith(".so")) return "native";
        return "file";
    }

    private boolean shouldIndexArtifact(Path file) {
        String normalized = file.toString().replace('\\', '/');
        return !normalized.contains("/workspace-snapshot/analysis/workspace-snapshot/");
    }

    private int compareArtifactTime(Path candidate, Map<String, Object> current) {
        try {
            FileTime candidateTime = Files.getLastModifiedTime(candidate);
            String currentPath = String.valueOf(current.getOrDefault("path", ""));
            if (currentPath.isBlank()) {
                return 1;
            }
            Path currentFile = Path.of(System.getProperty("user.dir")).resolve("output").resolve(currentPath);
            FileTime currentTime = Files.exists(currentFile) ? Files.getLastModifiedTime(currentFile) : FileTime.fromMillis(0);
            return candidateTime.compareTo(currentTime);
        } catch (IOException ignored) {
            return 0;
        }
    }

    private Map<String, Object> artifactSummary(Path analysisDir, Path file, String type) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("path", projectRelativeArtifact(file));
        summary.put("type", type);
        summary.put("name", file.getFileName().toString());
        return summary;
    }

    private String projectRelativeArtifact(Path path) {
        Path root = Path.of(System.getProperty("user.dir")).resolve("output");
        try {
            return root.relativize(path).toString().replace('\\', '/');
        } catch (Exception ignored) {
            return path.toString().replace('\\', '/');
        }
    }
}
