package com.fyr.r0re.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer {
    private static final String[] RUNTIME_MIGRATIONS = {
            """
            ALTER TABLE settings ADD COLUMN workspace_sync_enabled INTEGER NOT NULL DEFAULT 1
            """,
            """
            CREATE TABLE IF NOT EXISTS verification_intents (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                candidate_id TEXT NOT NULL REFERENCES completion_candidates(id) ON DELETE CASCADE,
                status TEXT NOT NULL,
                worker TEXT,
                prompt_group TEXT,
                tool TEXT,
                summary TEXT,
                artifact TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """,
            """
            INSERT OR IGNORE INTO verification_intents(id, project_id, candidate_id, status, worker, prompt_group, tool, summary, artifact, created_at, updated_at)
            SELECT 'v-' || id, project_id, id, verification_status, verifier_worker, NULL, NULL, NULL, artifact_path, created_at, COALESCE(verified_at, created_at)
            FROM completion_candidates
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_verification_intents_project_candidate
            ON verification_intents(project_id, candidate_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_runtime_events_project_event
            ON runtime_events(project_id, event_type, created_at)
            """,
            """
            UPDATE settings
            SET workspace_sync_enabled = COALESCE(workspace_sync_enabled, 1)
            """,
            """
            ALTER TABLE facts ADD COLUMN kind TEXT NOT NULL DEFAULT 'fact'
            """,
            """
            ALTER TABLE facts ADD COLUMN verified INTEGER NOT NULL DEFAULT 0
            """,
            """
            ALTER TABLE facts ADD COLUMN creator TEXT
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_facts_project_kind ON facts(project_id, kind)
            """
    };

    public DatabaseInitializer(
            DataSource dataSource,
            @Value("${r0re.storage.data-dir}") String dataDir
    ) throws IOException {
        Files.createDirectories(Path.of(dataDir));
        var resource = new ClassPathResource("schema.sql");
        String schema = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var jdbc = new JdbcTemplate(dataSource);
        for (String statement : schema.split(";")) {
            String sql = statement.trim();
            if (!sql.isEmpty()) {
                jdbc.execute(sql);
            }
        }
        for (String statement : RUNTIME_MIGRATIONS) {
            try {
                jdbc.execute(statement);
            } catch (Exception ignored) {
                // Runtime migrations must stay idempotent for existing SQLite files.
            }
        }
        jdbc.execute("PRAGMA foreign_keys = ON");
    }
}
