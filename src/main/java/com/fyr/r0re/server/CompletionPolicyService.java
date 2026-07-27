package com.fyr.r0re.server;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fyr.r0re.config.R0reProperties;
import com.fyr.r0re.dispatcher.DispatchConfig;
import com.fyr.r0re.protocol.Models.CompletionCandidate;
import com.fyr.r0re.protocol.Models.ProjectDetail;
import com.fyr.r0re.protocol.Models.RuntimeEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class CompletionPolicyService {
    private static final List<String> REVERSE_MARKERS = List.of(
            "reverse", "reversing", "reverse engineer", "crackme", "ctf", "apk",
            "android", "jni", "native", "smali", "jadx", "frida", "zygisk", ".so"
    );
    private static final List<String> PASS_MARKERS = List.of(
            "passed", "pass", "verified", "verify ok", "verification passed", "solver ok", "solver passed"
    );

    private final JdbcTemplate jdbc;
    private final R0reProperties properties;
    private final String dataDir;
    private final Path rootDir;
    private final YAMLMapper yamlMapper = new YAMLMapper();

    @Autowired
    public CompletionPolicyService(
            JdbcTemplate jdbc,
            R0reProperties properties,
            @Value("${r0re.storage.data-dir}") String dataDir
    ) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.dataDir = dataDir;
        this.rootDir = Path.of(System.getProperty("user.dir"));
    }

    CompletionPolicyService(JdbcTemplate jdbc, R0reProperties properties, String dataDir, Path rootDir) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.dataDir = dataDir;
        this.rootDir = rootDir;
    }

    public void assertCandidateCanFinalize(String projectId, ProjectDetail project, CompletionCandidate candidate) {
        if (!requiresVerifierForCompletion()) {
            return;
        }
        if (!isReverseProject(project)) {
            return;
        }
        if (candidate.verifier_worker() == null || candidate.verifier_worker().isBlank()) {
            throw HttpErrors.conflict("Reverse completion requires verifier worker evidence");
        }
        if (candidate.verified_at() == null || candidate.verified_at().isBlank()) {
            throw HttpErrors.conflict("Reverse completion requires verified timestamp");
        }
        if (!hasArtifactEvidence(projectId, candidate)) {
            throw HttpErrors.conflict("Reverse completion requires analysis artifact evidence");
        }
        if (!hasVerifierPassEvidence(projectId, candidate)) {
            throw HttpErrors.conflict("Reverse completion requires passed verifier or solver evidence");
        }
    }

    boolean isReverseProject(ProjectDetail project) {
        if (project == null || project.project() == null) {
            return false;
        }
        StringBuilder text = new StringBuilder();
        append(text, project.project().title());
        project.facts().forEach(fact -> {
            if ("origin".equals(fact.id()) || "goal".equals(fact.id())) {
                append(text, fact.description());
            }
        });
        String value = text.toString().toLowerCase(Locale.ROOT);
        return REVERSE_MARKERS.stream().anyMatch(value::contains);
    }

    private boolean requiresVerifierForCompletion() {
        return properties.getCompletion().isRequireVerifierForCompletion();
    }

    private boolean hasArtifactEvidence(String projectId, CompletionCandidate candidate) {
        if (candidate.artifact_path() != null && !candidate.artifact_path().isBlank()) {
            Path candidateArtifact = resolveAnalysisDir(projectId).resolve(candidate.artifact_path());
            if (Files.exists(candidateArtifact)) {
                return true;
            }
        }
        Path analysisDir = resolveAnalysisDir(projectId);
        if (!Files.isDirectory(analysisDir)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(analysisDir)) {
            return walk.anyMatch(Files::isRegularFile);
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean hasVerifierPassEvidence(String projectId, CompletionCandidate candidate) {
        // 事件级 artifact 与目录级产物二选一:目录里已有真实产物(由 hasArtifactEvidence 独立核验)时,
        // 不再要求 verifier 事件本身携带 artifact 元数据(规则 verifier 自动 approve 的事件不带)。
        boolean dirHasArtifacts = hasArtifactEvidence(projectId, candidate);
        return recentRuntimeEvents(projectId).stream()
                .filter(event -> sameVerifier(candidate, event))
                .anyMatch(event -> passesVerifierCheck(event, dirHasArtifacts));
    }

    private boolean sameVerifier(CompletionCandidate candidate, RuntimeEvent event) {
        if (event == null || candidate == null || candidate.verifier_worker() == null) {
            return false;
        }
        if (!candidate.verifier_worker().equals(event.worker())) {
            return false;
        }
        return event.created_at() == null
                || candidate.verified_at() == null
                || !Instant.parse(event.created_at()).isAfter(Instant.parse(candidate.verified_at()).plusSeconds(5));
    }

    private boolean passesVerifierCheck(RuntimeEvent event, boolean dirHasArtifacts) {
        String combined = (event.message() == null ? "" : event.message()) + " "
                + (event.step() == null ? "" : event.step()) + " "
                + (event.event_type() == null ? "" : event.event_type()) + " "
                + (event.tool() == null ? "" : event.tool());
        String lower = combined.toLowerCase(Locale.ROOT);
        boolean hasPassMarker = PASS_MARKERS.stream().anyMatch(lower::contains);
        boolean hasArtifact = dirHasArtifacts || (event.artifact() != null && !event.artifact().isBlank());
        return hasPassMarker && hasArtifact;
    }

    private List<RuntimeEvent> recentRuntimeEvents(String projectId) {
        return jdbc.query("""
                        SELECT * FROM runtime_events
                        WHERE project_id = ?
                        ORDER BY id DESC
                        LIMIT 80
                        """,
                runtimeEventMapper(), projectId);
    }

    private Path resolveAnalysisDir(String projectId) {
        DispatchConfig config = loadDispatchConfig();
        Path fallback = rootDir.resolve(Path.of("output/live-analysis", projectId));
        if (config != null && config.container() != null && config.container().volumes() != null) {
            for (Map.Entry<String, DispatchConfig.Volume> entry : config.container().volumes().entrySet()) {
                DispatchConfig.Volume volume = entry.getValue();
                if (volume != null && "/home/reverser/workspace/analysis".equals(volume.bind())) {
                    return Path.of(entry.getKey().replace("{project_id}", projectId == null ? "" : projectId));
                }
            }
        }
        return fallback;
    }

    private DispatchConfig loadDispatchConfig() {
        Path configPath = Path.of(properties.getDispatcher().getConfig());
        if (!configPath.isAbsolute()) {
            configPath = rootDir.resolve(configPath);
        }
        if (!Files.exists(configPath)) {
            return null;
        }
        try {
            return yamlMapper.readValue(configPath.toFile(), DispatchConfig.class);
        } catch (IOException ignored) {
            return null;
        }
    }

    private RowMapper<RuntimeEvent> runtimeEventMapper() {
        return (rs, rowNum) -> new RuntimeEvent(
                rs.getLong("id"),
                rs.getString("project_id"),
                rs.getString("scope_type"),
                rs.getString("scope_id"),
                rs.getString("worker"),
                rs.getString("event_type"),
                rs.getString("phase"),
                rs.getString("step"),
                rs.getString("message"),
                rs.getString("artifact"),
                rs.getString("tool"),
                integerValue(rs, "progress_current"),
                integerValue(rs, "progress_total"),
                rs.getString("created_at")
        );
    }

    private Integer integerValue(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private void append(StringBuilder text, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append(' ');
        }
        text.append(value.trim());
    }
}
