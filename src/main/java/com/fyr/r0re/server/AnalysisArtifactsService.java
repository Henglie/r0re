package com.fyr.r0re.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fyr.r0re.config.R0reProperties;
import com.fyr.r0re.dispatcher.DockerCli;
import com.fyr.r0re.dispatcher.DispatchConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import com.fyr.r0re.protocol.Models.ArtifactIndexGroup;
import com.fyr.r0re.protocol.Models.ArtifactSummary;
import com.fyr.r0re.protocol.Models.Fact;
import com.fyr.r0re.protocol.Models.Hint;
import com.fyr.r0re.protocol.Models.Intent;
import com.fyr.r0re.protocol.Models.JniMethodEntry;
import com.fyr.r0re.protocol.Models.ProjectDetail;

@Service
public class AnalysisArtifactsService {
    private static final Pattern TABLE_ROW = Pattern.compile("^\\|(.+)\\|$");
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("(0x[0-9a-fA-F]+|[A-Za-z0-9_.$]+\\.java:\\d+\\+?|[A-Za-z0-9_.$]+\\([^)]*\\)|sub_[0-9A-Fa-f]+|offset\\s+0x[0-9a-fA-F]+)");
    private static final List<String> SEARCH_FILE_SUFFIXES = List.of(".java", ".kt", ".smali", ".xml", ".txt", ".md");
    private static final Pattern JAVA_NATIVE_METHOD = Pattern.compile("\\bnative\\s+([\\w.$\\[\\]/<>]+)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(([^)]*)\\)");
    private static final Pattern SMALI_NATIVE_METHOD = Pattern.compile("^\\.method\\s+.*\\bnative\\b.*\\s([A-Za-z_$][A-Za-z0-9_$]*)\\(([^)]*)\\)(.+)$");
    private static final Pattern LOAD_LIBRARY_CALL = Pattern.compile("System\\.loadLibrary\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern LOAD_CALL = Pattern.compile("System\\.load\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern FLAG_CANDIDATE = Pattern.compile("\\bflag\\s*=\\s*([A-Za-z0-9_{}\\-]+)", Pattern.CASE_INSENSITIVE);

    private final R0reProperties properties;
    private final R0reService service;
    private final ObjectMapper jsonMapper;
    private final YAMLMapper yamlMapper = new YAMLMapper();

    public AnalysisArtifactsService(R0reProperties properties, R0reService service, ObjectMapper jsonMapper) {
        this.properties = properties;
        this.service = service;
        this.jsonMapper = jsonMapper;
    }

    public Map<String, Object> loadTraceView(String projectId) {
        return loadTraceView(projectId, 3);
    }

    public Map<String, Object> loadTraceView(String projectId, int limit) {
        ResolvedPaths paths = resolvePaths(projectId);
        int resolvedLimit = Math.max(1, limit);
        List<Map<String, Object>> traces = loadStructuredTraceFiles(paths.analysisDir(), resolvedLimit);
        Map<String, Object> llmCounts = loadLlmCountView(paths.analysisDir());
        if (traces.isEmpty()) {
            traces = loadPiSessionFallback(paths.piSessionDir(), resolvedLimit);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project_id", projectId);
        payload.put("traces", traces);
        payload.put("trace_limit", resolvedLimit);
        payload.put("trace_count", traces.size());
        payload.put("llm_counts", llmCounts);
        payload.put("analysis_dir", paths.analysisDir().toString());
        payload.put("pi_session_dir", paths.piSessionDir().toString());
        return payload;
    }

    public Map<String, Object> loadDocumentView(String projectId) {
        ResolvedPaths paths = resolvePaths(projectId);
        String zh = readText(paths.analysisDir().resolve("report_zh.md"));
        String en = readText(paths.analysisDir().resolve("report_en.md"));
        String fallbackSummary = readText(paths.analysisDir().resolve("timeline.txt"));
        ProjectDetail project = service.getProject(projectId);

        zh = mergeDynamicDocument(project, zh, true);
        en = mergeDynamicDocument(project, en, false);

        List<Map<String, String>> functionIndex = new ArrayList<>();
        functionIndex.addAll(extractFunctionIndex(zh));
        functionIndex.addAll(extractFunctionIndex(en));
        functionIndex = dedupeFunctionIndex(functionIndex);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project_id", projectId);
        payload.put("zh_report", zh);
        payload.put("en_report", en);
        payload.put("timeline", fallbackSummary);
        payload.put("function_index", functionIndex);
        payload.put("analysis_dir", paths.analysisDir().toString());
        return payload;
    }

    private String mergeDynamicDocument(ProjectDetail project, String report, boolean zh) {
        String dynamic = buildDynamicDocument(project, report, zh);
        if (report == null || report.isBlank()) {
            return dynamic;
        }
        return dynamic + "\n\n---\n\n" + report.trim() + "\n";
    }

    private String buildDynamicDocument(ProjectDetail project, String report, boolean zh) {
        List<String> rejectedFlags = rejectedFlagCandidates(project);
        Intent conclusion = latestGoalConclusion(project);
        boolean completed = "completed".equals(project.project().status());
        StringBuilder out = new StringBuilder();
        if (zh) {
            out.append("# 当前项目状态（自动生成）\n\n");
            out.append("- 项目：").append(project.project().id()).append(" / ").append(project.project().title()).append("\n");
            out.append("- 状态：").append(project.project().status()).append("\n");
            out.append("- 当前结论：");
            if (completed && conclusion != null) {
                out.append(trimSummary(conclusion.description(), 500)).append("\n");
            } else {
                out.append("尚未形成可接受的最终结论；下方原始报告可能是旧阶段产物。\n");
            }
            appendRejectedFlags(out, rejectedFlags, report, true);
            appendLatestHints(out, project.hints(), true);
            appendLatestFacts(out, project.facts(), true);
        } else {
            out.append("# Current Project State (Generated)\n\n");
            out.append("- Project: ").append(project.project().id()).append(" / ").append(project.project().title()).append("\n");
            out.append("- Status: ").append(project.project().status()).append("\n");
            out.append("- Current conclusion: ");
            if (completed && conclusion != null) {
                out.append(trimSummary(conclusion.description(), 500)).append("\n");
            } else {
                out.append("No accepted final conclusion yet; the raw report below may be stale.\n");
            }
            appendRejectedFlags(out, rejectedFlags, report, false);
            appendLatestHints(out, project.hints(), false);
            appendLatestFacts(out, project.facts(), false);
        }
        return out.toString().trim() + "\n";
    }

    private Intent latestGoalConclusion(ProjectDetail project) {
        Intent latest = null;
        for (Intent intent : project.intents()) {
            if (!"goal".equals(intent.to()) || intent.concluded_at() == null) {
                continue;
            }
            if (latest == null || intent.concluded_at().compareTo(latest.concluded_at()) > 0) {
                latest = intent;
            }
        }
        return latest;
    }

    private List<String> rejectedFlagCandidates(ProjectDetail project) {
        List<String> values = new ArrayList<>();
        for (Fact fact : project.facts()) {
            String text = fact.description() == null ? "" : fact.description();
            String lower = text.toLowerCase(Locale.ROOT);
            if (!lower.contains("wrong") && !lower.contains("reject") && !lower.contains("invalid")
                    && !text.contains("错误") && !text.contains("否定") && !text.contains("拒绝")) {
                continue;
            }
            Matcher matcher = FLAG_CANDIDATE.matcher(text);
            while (matcher.find()) {
                String value = matcher.group(1);
                if (!values.contains(value)) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private void appendRejectedFlags(StringBuilder out, List<String> rejectedFlags, String report, boolean zh) {
        if (rejectedFlags.isEmpty()) {
            return;
        }
        out.append(zh ? "- 已确认错误候选：" : "- Rejected candidates: ");
        out.append(String.join(", ", rejectedFlags)).append("\n");
        if (report != null && rejectedFlags.stream().anyMatch(report::contains)) {
            out.append(zh
                    ? "- 注意：下方原始报告仍包含已确认错误的候选值，应以本自动状态为准。\n"
                    : "- Note: the raw report below still contains a rejected candidate; trust this generated state instead.\n");
        }
    }

    private void appendLatestHints(StringBuilder out, List<Hint> hints, boolean zh) {
        if (hints.isEmpty()) {
            return;
        }
        Hint latest = hints.get(hints.size() - 1);
        out.append(zh ? "- 最新 Hint：" : "- Latest hint: ");
        out.append(trimSummary(latest.content(), 260)).append("\n");
    }

    private void appendLatestFacts(StringBuilder out, List<Fact> facts, boolean zh) {
        List<Fact> relevant = facts.stream()
                .filter(fact -> !"origin".equals(fact.id()) && !"goal".equals(fact.id()))
                .toList();
        if (relevant.isEmpty()) {
            return;
        }
        out.append(zh ? "\n## 最新事实\n\n" : "\n## Latest Facts\n\n");
        int start = Math.max(0, relevant.size() - 5);
        for (int i = start; i < relevant.size(); i++) {
            Fact fact = relevant.get(i);
            out.append("- ").append(fact.id()).append(": ").append(trimSummary(fact.description(), 360)).append("\n");
        }
    }

    public Map<String, Object> loadArtifactsView(String projectId, int limit) {
        ResolvedPaths paths = resolvePaths(projectId);
        int resolvedLimit = Math.max(1, limit);
        List<ArtifactSummary> files = collectArtifacts(paths.analysisDir(), resolvedLimit);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project_id", projectId);
        payload.put("analysis_dir", paths.analysisDir().toString());
        payload.put("artifacts", files);
        payload.put("artifact_count", files.size());
        return payload;
    }

    public Map<String, Object> loadArtifactIndexView(String projectId, int perGroupLimit) {
        ResolvedPaths paths = resolvePaths(projectId);
        int resolvedLimit = Math.max(1, perGroupLimit);
        List<ArtifactSummary> allArtifacts = collectArtifacts(paths.analysisDir(), Integer.MAX_VALUE);

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("report", "Reports");
        labels.put("trace", "LLM Traces");
        labels.put("count", "LLM Count Logs");
        labels.put("snapshot", "Workspace Snapshots");
        labels.put("native", "Native Files");
        labels.put("metadata", "Metadata");
        labels.put("text", "Text");
        labels.put("binary", "Binary");
        labels.put("file", "Other Files");

        List<ArtifactIndexGroup> groups = new ArrayList<>();
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            String key = entry.getKey();
            List<ArtifactSummary> matching = allArtifacts.stream()
                    .filter(artifact -> key.equals(artifact.type()))
                    .limit(resolvedLimit)
                    .toList();
            int totalCount = (int) allArtifacts.stream().filter(artifact -> key.equals(artifact.type())).count();
            if (totalCount == 0) {
                continue;
            }
            groups.add(new ArtifactIndexGroup(key, entry.getValue(), totalCount, matching));
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        for (ArtifactIndexGroup group : groups) {
            counts.put(group.key(), group.count());
        }

        Map<String, Object> highlights = new LinkedHashMap<>();
        highlights.put("latest_report", latestArtifact(allArtifacts, artifact -> "report".equals(artifact.type())));
        highlights.put("latest_trace", latestArtifact(allArtifacts, artifact -> "trace".equals(artifact.type())));
        highlights.put("latest_snapshot", latestArtifact(allArtifacts, artifact -> "snapshot".equals(artifact.type())));
        highlights.put("latest_native", latestArtifact(allArtifacts, artifact -> "native".equals(artifact.type())));
        highlights.put("latest_metadata", latestArtifact(allArtifacts, artifact -> "metadata".equals(artifact.type())));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project_id", projectId);
        payload.put("analysis_dir", paths.analysisDir().toString());
        payload.put("groups", groups);
        payload.put("group_count", groups.size());
        payload.put("artifact_count", allArtifacts.size());
        payload.put("counts", counts);
        payload.put("highlights", highlights);
        return payload;
    }

    public Map<String, Object> searchJadxView(String projectId, String query, int limit) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty()) {
            throw HttpErrors.badRequest("query must not be blank");
        }
        ResolvedPaths paths = resolvePaths(projectId);
        int resolvedLimit = Math.max(1, limit);
        List<Path> roots = locateSearchRoots(paths.analysisDir(), "jadx_out", "jadx_real", "smali_real", "apktool_out");
        List<Map<String, Object>> matches = new ArrayList<>();
        String lowerQuery = normalizedQuery.toLowerCase(Locale.ROOT);

        for (Path root : roots) {
            if (matches.size() >= resolvedLimit) {
                break;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(Files::isRegularFile)
                        .filter(this::searchableFile)
                        .sorted()
                        .toList()) {
                    if (matches.size() >= resolvedLimit) {
                        break;
                    }
                    scanFileForQuery(paths.analysisDir(), file, lowerQuery, normalizedQuery, resolvedLimit, matches);
                }
            } catch (IOException ignored) {
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project_id", projectId);
        payload.put("query", normalizedQuery);
        payload.put("search_roots", roots.stream().map(Path::toString).toList());
        payload.put("matches", matches);
        payload.put("match_count", matches.size());
        return payload;
    }

    public Map<String, Object> loadJniMethodMapView(String projectId, int limit) {
        ResolvedPaths paths = resolvePaths(projectId);
        int resolvedLimit = Math.max(1, limit);
        List<Path> roots = locateSearchRoots(paths.analysisDir(), "jadx_out", "jadx_real", "smali_real", "apktool_out");
        Map<String, List<String>> librariesByClass = new LinkedHashMap<>();
        List<Map<String, Object>> libraryLoads = new ArrayList<>();

        for (Path root : roots) {
            collectLibraryLoads(paths.analysisDir(), root, librariesByClass, libraryLoads);
        }

        List<JniMethodEntry> entries = new ArrayList<>();
        for (Path root : roots) {
            if (entries.size() >= resolvedLimit) {
                break;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(Files::isRegularFile)
                        .filter(path -> {
                            String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
                            return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".smali");
                        })
                        .sorted()
                        .toList()) {
                    if (entries.size() >= resolvedLimit) {
                        break;
                    }
                    collectJniEntries(paths.analysisDir(), file, librariesByClass, entries, resolvedLimit);
                }
            } catch (IOException ignored) {
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project_id", projectId);
        payload.put("search_roots", roots.stream().map(Path::toString).toList());
        payload.put("entries", entries);
        payload.put("entry_count", entries.size());
        payload.put("library_loads", libraryLoads);
        payload.put("library_load_count", libraryLoads.size());
        return payload;
    }

    public Map<String, Object> snapshotWorkspace(String projectId) {
        DispatchConfig config = loadDispatchConfig();
        if (config == null || config.container() == null) {
            throw HttpErrors.conflict("dispatcher container config is not available");
        }
        ResolvedPaths paths = resolvePaths(projectId);
        DockerCli docker = new DockerCli(config.container());
        String container = docker.containerName(projectId);
        String state = docker.inspectState(container);
        if (state == null) {
            throw HttpErrors.notFound("worker container not found for " + projectId);
        }
        try {
            Path snapshotDir = paths.analysisDir().resolve("workspace-snapshot");
            docker.copyDirectoryFromContainer(container, "/home/reverser/workspace", snapshotDir);
            pruneNestedSnapshots(snapshotDir);
            Path manifestPath = paths.analysisDir().resolve("workspace-files.txt");
            writeWorkspaceManifest(snapshotDir, manifestPath);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("project_id", projectId);
            payload.put("container", container);
            payload.put("container_state", state);
            payload.put("snapshot_dir", snapshotDir.toString());
            payload.put("manifest", manifestPath.toString());
            payload.put("file_count", countFiles(snapshotDir));
            return payload;
        } catch (IOException exc) {
            throw HttpErrors.conflict("workspace snapshot failed: " + exc.getMessage());
        } catch (RuntimeException exc) {
            throw HttpErrors.conflict("workspace snapshot failed: " + exc.getMessage());
        }
    }

    private List<Map<String, Object>> loadStructuredTraceFiles(Path analysisDir, int limit) {
        Path traceDir = analysisDir.resolve("llm-traces");
        if (!Files.isDirectory(traceDir)) {
            return List.of();
        }
        List<Map<String, Object>> traces = new ArrayList<>();
        try (Stream<Path> stream = Files.list(traceDir)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.reverseOrder())
                    .limit(limit)
                    .toList()) {
                try {
                    Map<String, Object> trace = jsonMapper.readValue(file.toFile(), new TypeReference<>() {});
                    trace.put("source", "trace_file");
                    trace.put("file", file.getFileName().toString());
                    trace.put("steps", buildStepsFromTrace(trace));
                    traces.add(trace);
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        traces.sort(Comparator.comparing(trace -> String.valueOf(trace.getOrDefault("started_at", ""))));
        return traces;
    }

    private String artifactType(Path path) {
        String relative = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (relative.contains("/llm-traces/")) return "trace";
        if (relative.contains("/llm-counts/")) return "count";
        if (relative.contains("/workspace-snapshot/")) return "snapshot";
        if (name.endsWith(".md")) return "report";
        if (name.endsWith(".json") || name.endsWith(".jsonl") || name.endsWith(".yaml") || name.endsWith(".yml")) return "metadata";
        if (name.endsWith(".txt")) return "text";
        if (name.endsWith(".bin")) return "binary";
        if (name.endsWith(".so")) return "native";
        return "file";
    }

    private List<ArtifactSummary> collectArtifacts(Path analysisDir, int limit) {
        List<ArtifactSummary> files = new ArrayList<>();
        if (!Files.isDirectory(analysisDir)) {
            return files;
        }
        try (Stream<Path> stream = Files.walk(analysisDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(this::safeModifiedTime).reversed())
                    .limit(limit)
                    .map(path -> new ArtifactSummary(
                            analysisDir.relativize(path).toString(),
                            path.getFileName().toString(),
                            artifactType(analysisDir.relativize(path)),
                            safeSize(path),
                            safeModifiedTime(path).toString()))
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private ArtifactSummary latestArtifact(List<ArtifactSummary> artifacts, Function<ArtifactSummary, Boolean> predicate) {
        for (ArtifactSummary artifact : artifacts) {
            if (predicate.apply(artifact)) {
                return artifact;
            }
        }
        return null;
    }

    private List<Path> locateSearchRoots(Path analysisDir, String... names) {
        List<Path> roots = new ArrayList<>();
        Path snapshotRoot = analysisDir.resolve("workspace-snapshot");
        if (!Files.isDirectory(snapshotRoot)) {
            return roots;
        }
        try (Stream<Path> stream = Files.walk(snapshotRoot)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> Arrays.stream(names).anyMatch(name -> name.equals(path.getFileName().toString())))
                    .sorted()
                    .forEach(roots::add);
        } catch (IOException ignored) {
        }
        return roots;
    }

    private boolean searchableFile(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return SEARCH_FILE_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    private void scanFileForQuery(Path analysisDir, Path file, String lowerQuery, String rawQuery, int limit, List<Map<String, Object>> matches) {
        try (Stream<String> lines = Files.lines(file)) {
            final int[] lineNumber = {0};
            for (String line : lines.toList()) {
                lineNumber[0] += 1;
                if (!line.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                    continue;
                }
                Map<String, Object> match = new LinkedHashMap<>();
                match.put("path", analysisDir.relativize(file).toString());
                match.put("name", file.getFileName().toString());
                match.put("line", lineNumber[0]);
                match.put("query", rawQuery);
                match.put("snippet", trimSummary(line, 240));
                matches.add(match);
                if (matches.size() >= limit) {
                    return;
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void collectLibraryLoads(Path analysisDir, Path root, Map<String, List<String>> librariesByClass, List<Map<String, Object>> libraryLoads) {
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return lower.endsWith(".java") || lower.endsWith(".kt");
                    })
                    .sorted()
                    .toList()) {
                String className = deriveClassName(root, file);
                if (className == null || className.isBlank()) {
                    continue;
                }
                try {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        Matcher loadLibrary = LOAD_LIBRARY_CALL.matcher(line);
                        Matcher load = LOAD_CALL.matcher(line);
                        while (loadLibrary.find()) {
                            recordLibraryLoad(analysisDir, file, className, i + 1, loadLibrary.group(1), "loadLibrary", librariesByClass, libraryLoads);
                        }
                        while (load.find()) {
                            recordLibraryLoad(analysisDir, file, className, i + 1, load.group(1), "load", librariesByClass, libraryLoads);
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void recordLibraryLoad(Path analysisDir, Path file, String className, int lineNumber, String library, String callType,
                                   Map<String, List<String>> librariesByClass, List<Map<String, Object>> libraryLoads) {
        List<String> values = librariesByClass.computeIfAbsent(className, ignored -> new ArrayList<>());
        if (!values.contains(library)) {
            values.add(library);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("class_name", className);
        row.put("library", library);
        row.put("call_type", callType);
        row.put("source_path", analysisDir.relativize(file).toString());
        row.put("line", lineNumber);
        libraryLoads.add(row);
    }

    private void collectJniEntries(Path analysisDir, Path file, Map<String, List<String>> librariesByClass,
                                   List<JniMethodEntry> entries, int limit) {
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String className = deriveClassName(file.getParent(), file);
        if (className == null || className.isBlank()) {
            className = fallbackClassName(file);
        }
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (entries.size() >= limit) {
                    return;
                }
                String line = lines.get(i);
                if (lower.endsWith(".smali")) {
                    Matcher matcher = SMALI_NATIVE_METHOD.matcher(line.trim());
                    if (!matcher.find()) {
                        continue;
                    }
                    String methodName = matcher.group(1);
                    String args = matcher.group(2);
                    String returnType = matcher.group(3).trim();
                    String signature = "(" + args + ")" + returnType;
                    entries.add(new JniMethodEntry(
                            className,
                            methodName,
                            signature,
                            returnType,
                            analysisDir.relativize(file).toString(),
                            i + 1,
                            "smali",
                            probableJniSymbol(className, methodName),
                            librariesByClass.getOrDefault(className, List.of())
                    ));
                    continue;
                }
                Matcher matcher = JAVA_NATIVE_METHOD.matcher(line);
                if (!matcher.find()) {
                    continue;
                }
                String returnType = matcher.group(1).trim();
                String methodName = matcher.group(2).trim();
                String args = matcher.group(3).trim();
                entries.add(new JniMethodEntry(
                        className,
                        methodName,
                        "(" + args + ")",
                        returnType,
                        analysisDir.relativize(file).toString(),
                        i + 1,
                        lower.endsWith(".kt") ? "kotlin" : "java",
                        probableJniSymbol(className, methodName),
                        librariesByClass.getOrDefault(className, List.of())
                ));
            }
        } catch (IOException ignored) {
        }
    }

    private String deriveClassName(Path root, Path file) {
        String normalized = file.toString().replace('\\', '/');
        int sourcesIndex = normalized.indexOf("/sources/");
        if (sourcesIndex >= 0) {
            String tail = normalized.substring(sourcesIndex + "/sources/".length());
            return stripExtension(tail).replace('/', '.');
        }
        int smaliIndex = normalized.indexOf("/smali");
        if (smaliIndex >= 0) {
            int start = normalized.indexOf('/', smaliIndex + 1);
            if (start >= 0 && start + 1 < normalized.length()) {
                return stripExtension(normalized.substring(start + 1)).replace('/', '.');
            }
        }
        String rootNormalized = root.toString().replace('\\', '/');
        if (normalized.startsWith(rootNormalized + "/")) {
            return stripExtension(normalized.substring(rootNormalized.length() + 1)).replace('/', '.');
        }
        return fallbackClassName(file);
    }

    private String fallbackClassName(Path file) {
        String name = file.getFileName().toString();
        return stripExtension(name);
    }

    private String stripExtension(String path) {
        int idx = path.lastIndexOf('.');
        return idx >= 0 ? path.substring(0, idx) : path;
    }

    private String probableJniSymbol(String className, String methodName) {
        String encodedClass = className.replace("_", "_1").replace("$", "_00024").replace(".", "_");
        String encodedMethod = methodName.replace("_", "_1");
        return "Java_" + encodedClass + "_" + encodedMethod;
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

    private FileTime safeModifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException ignored) {
            return FileTime.fromMillis(0);
        }
    }

    private long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private List<Map<String, Object>> buildStepsFromTrace(Map<String, Object> trace) {
        List<Map<String, Object>> steps = new ArrayList<>();
        String prompt = stringValue(trace.get("prompt"));
        if (!prompt.isBlank()) {
            steps.add(step("prompt", stringValue(trace.get("started_at")), "Prompt", prompt, "sent"));
        }

        List<Map<String, Object>> fromEvents = parsePiEvents(stringValue(trace.get("stdout")));
        if (!fromEvents.isEmpty()) {
            steps.addAll(fromEvents);
        } else {
            String response = stringValue(trace.get("response_text"));
            if (!response.isBlank()) {
                steps.add(step("response", stringValue(trace.get("finished_at")), "Response", response, statusValue(trace.get("status"))));
            }
            String stderr = stringValue(trace.get("stderr"));
            if (!stderr.isBlank()) {
                steps.add(step("stderr", stringValue(trace.get("finished_at")), "stderr", stderr, "error"));
            }
        }

        Object parsed = trace.get("parsed_result");
        if (parsed != null) {
            try {
                String pretty = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
                steps.add(step("parsed_result", stringValue(trace.get("finished_at")), "Parsed Result", pretty, "ok"));
            } catch (Exception ignored) {
            }
        }
        return steps;
    }

    private List<Map<String, Object>> loadPiSessionFallback(Path piSessionDir, int limit) {
        if (!Files.isDirectory(piSessionDir)) {
            return List.of();
        }
        List<Map<String, Object>> traces = new ArrayList<>();
        try (Stream<Path> workers = Files.list(piSessionDir)) {
            for (Path workerDir : workers.filter(Files::isDirectory).sorted().toList()) {
                Path sessionsDir = workerDir.resolve("sessions");
                if (!Files.isDirectory(sessionsDir)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(sessionsDir)) {
                    for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                            .sorted(Comparator.reverseOrder())
                            .limit(limit)
                            .toList()) {
                        String raw = readText(file);
                        List<Map<String, Object>> steps = parsePiEvents(raw);
                        if (steps.isEmpty()) {
                            continue;
                        }
                        Map<String, Object> trace = new LinkedHashMap<>();
                        trace.put("id", file.getFileName().toString().replace(".jsonl", ""));
                        trace.put("source", "pi_session");
                        trace.put("worker_name", workerDir.getFileName().toString());
                        trace.put("phase", inferPhaseFromPrompt(steps));
                        trace.put("started_at", stringValue(steps.get(0).get("timestamp")));
                        trace.put("status", inferStatusFromSteps(steps));
                        trace.put("session_id", extractSessionId(raw));
                        trace.put("prompt", firstStepContent(steps, "prompt"));
                        trace.put("response_text", firstStepContent(steps, "response"));
                        trace.put("file", file.getFileName().toString());
                        trace.put("steps", steps);
                        traces.add(trace);
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        traces.sort(Comparator.comparing(trace -> String.valueOf(trace.getOrDefault("started_at", ""))));
        return traces;
    }

    private Map<String, Object> loadLlmCountView(Path analysisDir) {
        Path countDir = analysisDir.resolve("llm-counts");
        if (!Files.isDirectory(countDir)) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count_dir", countDir.toString());
        payload.put("summary", readJson(countDir.resolve("summary.json")));
        payload.put("recent_events", readRecentJsonLines(countDir.resolve("events.jsonl"), 50));
        return payload;
    }

    private Map<String, Object> readJson(Path path) {
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(path.toFile(), new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> readRecentJsonLines(Path path, int maxItems) {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(path);
            int from = Math.max(0, lines.size() - Math.max(1, maxItems));
            List<Map<String, Object>> items = new ArrayList<>();
            for (String line : lines.subList(from, lines.size())) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    items.add(jsonMapper.readValue(line, new TypeReference<>() {}));
                } catch (Exception ignored) {
                }
            }
            return items;
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> parsePiEvents(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            try {
                JsonNode event = jsonMapper.readTree(trimmed);
                String type = event.path("type").asText("");
                if ("message".equals(type)) {
                    JsonNode message = event.path("message");
                    String role = message.path("role").asText("");
                    String text = extractMessageText(message.path("content"));
                    String timestamp = event.path("timestamp").asText("");
                    if ("user".equals(role) && !text.isBlank()) {
                        steps.add(step("prompt", timestamp, "Prompt", text, "sent"));
                    } else if ("assistant".equals(role)) {
                        String errorMessage = message.path("errorMessage").asText("");
                        String stopReason = message.path("stopReason").asText("");
                        String content = !text.isBlank() ? text : errorMessage;
                        if (!content.isBlank()) {
                            steps.add(step("response", timestamp, "Response", content, stopReason.isBlank() ? "received" : stopReason));
                        }
                    }
                } else if ("turn_end".equals(type)) {
                    JsonNode message = event.path("message");
                    if ("assistant".equals(message.path("role").asText())) {
                        String content = extractMessageText(message.path("content"));
                        if (!content.isBlank()) {
                            steps.add(step("turn_end", event.path("timestamp").asText(""), "Turn End", content, "received"));
                        }
                    }
                } else if ("agent_end".equals(type)) {
                    JsonNode messages = event.path("messages");
                    if (messages.isArray() && !messages.isEmpty()) {
                        JsonNode last = messages.get(messages.size() - 1);
                        String content = extractMessageText(last.path("content"));
                        if (!content.isBlank()) {
                            steps.add(step("agent_end", event.path("timestamp").asText(""), "Agent End", content, "received"));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return dedupeAdjacentSteps(steps);
    }

    private List<Map<String, Object>> dedupeAdjacentSteps(List<Map<String, Object>> steps) {
        List<Map<String, Object>> deduped = new ArrayList<>();
        String lastFingerprint = null;
        for (Map<String, Object> step : steps) {
            String fingerprint = stringValue(step.get("kind")) + "\n" + stringValue(step.get("content"));
            if (!fingerprint.equals(lastFingerprint)) {
                deduped.add(step);
                lastFingerprint = fingerprint;
            }
        }
        return deduped;
    }

    private String extractMessageText(JsonNode content) {
        if (!content.isArray()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode item : content) {
            if ("text".equals(item.path("type").asText(""))) {
                String text = item.path("text").asText("");
                if (!text.isBlank()) {
                    parts.add(text);
                }
            }
        }
        return String.join("\n", parts).trim();
    }

    private Map<String, Object> step(String kind, String timestamp, String title, String content, String status) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("kind", kind);
        step.put("timestamp", timestamp);
        step.put("title", title);
        step.put("content", content);
        step.put("status", status);
        return step;
    }

    private String inferPhaseFromPrompt(List<Map<String, Object>> steps) {
        String prompt = firstStepContent(steps, "prompt").toLowerCase(Locale.ROOT);
        if (prompt.contains("conclude phase")) {
            return "conclude";
        }
        if (prompt.contains("## origin") && prompt.contains("## goal") && prompt.contains("## hints")) {
            return "bootstrap";
        }
        if (prompt.contains("max_intents")) {
            return "reason";
        }
        if (prompt.contains("intent")) {
            return "explore";
        }
        return "session";
    }

    private String inferStatusFromSteps(List<Map<String, Object>> steps) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            String status = statusValue(steps.get(i).get("status"));
            if ("error".equals(status)) {
                return "error";
            }
            if ("received".equals(status) || "ok".equals(status)) {
                return "success";
            }
        }
        return "unknown";
    }

    private String extractSessionId(String raw) {
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            try {
                JsonNode event = jsonMapper.readTree(trimmed);
                if ("session".equals(event.path("type").asText(""))) {
                    return event.path("id").asText("");
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private String firstStepContent(List<Map<String, Object>> steps, String kind) {
        for (Map<String, Object> step : steps) {
            if (kind.equals(step.get("kind"))) {
                return stringValue(step.get("content"));
            }
        }
        return "";
    }

    private List<Map<String, String>> extractFunctionIndex(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Map<String, String>> items = new ArrayList<>();
        String[] lines = text.split("\\R");
        boolean inLocationTable = false;
        for (String line : lines) {
            String normalized = line.toLowerCase(Locale.ROOT);
            // Detect table with function/symbol info
            if (normalized.contains("符号 / 地址") || normalized.contains("symbol / address")
                    || normalized.contains("符号") && normalized.contains("地址")
                    || normalized.contains("symbol") && normalized.contains("location")) {
                inLocationTable = true;
                continue;
            }
            // Close table mode when line does NOT start with |
            if (inLocationTable && !line.trim().startsWith("|")) {
                inLocationTable = false;
            }
            // Skip separator rows like | --- | --- |
            if (inLocationTable && line.contains("---")) {
                continue;
            }
            Matcher tableMatcher = TABLE_ROW.matcher(line);
            if (inLocationTable && tableMatcher.matches()) {
                String row = tableMatcher.group(1);
                String[] cells = splitTableCells(row);
                if (cells.length >= 2) {
                    // First cell: "symbol @ address", last cell: description
                    String firstCell = cells[0].trim();
                    String descCell = cells[cells.length - 1].trim();
                    // Extract symbol and address from first cell
                    String symbol = "";
                    String location = "";
                    // Pattern: "fcn.00002658 @ 0x2658" or "Java_..._method01 @ 0x33f4"
                    String cellContent = stripCode(firstCell);
                    Matcher addrMatcher = ADDRESS_PATTERN.matcher(cellContent);
                    if (addrMatcher.find()) {
                        location = addrMatcher.group(1);
                        symbol = cellContent.replace(location, "").replace("@", "").trim();
                    } else {
                        // No address in first cell, just symbol
                        symbol = cellContent;
                    }
                    // Also check for backtick in first cell
                    String symFromBacktick = firstBacktick(firstCell);
                    if (!symFromBacktick.isBlank()) {
                        symbol = symFromBacktick;
                    }
                    String summary = stripCode(descCell);
                    // Filter header rows
                    if (!symbol.equalsIgnoreCase("symbol") && !symbol.equalsIgnoreCase("class/method")
                            && !symbol.equalsIgnoreCase("method") && !symbol.isBlank()
                            && !summary.equalsIgnoreCase("description") && !summary.equalsIgnoreCase("作用")) {
                        items.add(functionItem(location, symbol, summary));
                    }
                }
                continue;
            }

            // Fallback disabled - only parse from tables
        }
        return items;
    }

    private String[] splitTableCells(String row) {
        // row is the content between outer pipes, e.g. "symbol @ addr | description"
        // Split on | (regex escaped)
        String[] parts = row.split("\\|");
        List<String> cells = new ArrayList<>();
        for (String p : parts) {
            cells.add(p.trim());
        }
        return cells.toArray(new String[0]);
    }

    private List<Map<String, String>> dedupeFunctionIndex(List<Map<String, String>> items) {
        Map<String, Map<String, String>> unique = new LinkedHashMap<>();
        for (Map<String, String> item : items) {
            String key = item.getOrDefault("location", "") + "|" + item.getOrDefault("symbol", "");
            unique.putIfAbsent(key, item);
        }
        return new ArrayList<>(unique.values());
    }

    private Map<String, String> functionItem(String location, String symbol, String summary) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("location", location == null ? "" : location.trim());
        item.put("symbol", symbol == null ? "" : symbol.trim());
        item.put("summary", summary == null ? "" : summary.trim());
        return item;
    }

    private String firstBacktick(String line) {
        int first = line.indexOf('`');
        if (first < 0) {
            return "";
        }
        int second = line.indexOf('`', first + 1);
        if (second < 0) {
            return "";
        }
        return line.substring(first + 1, second).trim();
    }

    private String stripCode(String value) {
        return value == null ? "" : value.replace("`", "").trim();
    }

    private String statusValue(Object value) {
        String text = stringValue(value);
        return "error".equals(text) ? "error" : text;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String readText(Path path) {
        if (!Files.exists(path)) {
            return "";
        }
        try {
            return Files.readString(path);
        } catch (IOException ignored) {
            return "";
        }
    }

    private ResolvedPaths resolvePaths(String projectId) {
        DispatchConfig config = loadDispatchConfig();
        Path analysisDir = resolveVolumePath(config, projectId, "/home/reverser/workspace/analysis", Path.of("output/live-analysis", projectId));
        Path piSessionDir = resolveVolumePath(config, projectId, "/tmp/cairn-pi", Path.of("output/pi-sessions", projectId));
        return new ResolvedPaths(analysisDir, piSessionDir);
    }

    private DispatchConfig loadDispatchConfig() {
        Path configPath = Path.of(properties.getDispatcher().getConfig());
        if (!Files.exists(configPath)) {
            return null;
        }
        try {
            return yamlMapper.readValue(configPath.toFile(), DispatchConfig.class);
        } catch (IOException ignored) {
            return null;
        }
    }

    private Path resolveVolumePath(DispatchConfig config, String projectId, String bindTarget, Path fallback) {
        if (config != null && config.container() != null && config.container().volumes() != null) {
            for (Map.Entry<String, DispatchConfig.Volume> entry : config.container().volumes().entrySet()) {
                DispatchConfig.Volume volume = entry.getValue();
                if (volume != null && bindTarget.equals(volume.bind())) {
                    return Path.of(entry.getKey().replace("{project_id}", projectId == null ? "" : projectId));
                }
            }
        }
        return fallback;
    }

    private void writeWorkspaceManifest(Path snapshotDir, Path manifestPath) throws IOException {
        if (snapshotDir == null || !Files.isDirectory(snapshotDir)) {
            return;
        }
        List<String> lines = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(snapshotDir)) {
            walk.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> lines.add(snapshotDir.relativize(path).toString()));
        }
        Files.write(manifestPath, lines);
    }

    private long countFiles(Path snapshotDir) throws IOException {
        if (snapshotDir == null || !Files.isDirectory(snapshotDir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(snapshotDir)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private void pruneNestedSnapshots(Path snapshotDir) throws IOException {
        if (snapshotDir == null || !Files.isDirectory(snapshotDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(snapshotDir)) {
            walk.filter(Files::isDirectory)
                    .filter(path -> !path.equals(snapshotDir))
                    .filter(path -> "workspace-snapshot".equals(path.getFileName().toString()))
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            deleteRecursively(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private record ResolvedPaths(Path analysisDir, Path piSessionDir) {
    }
}
