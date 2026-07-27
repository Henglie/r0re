package com.fyr.r0re.server;

import com.fyr.r0re.config.R0reProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ServeStartupProjectState implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ServeStartupProjectState.class);

    private final JdbcTemplate jdbc;
    private final R0reProperties properties;

    public ServeStartupProjectState(JdbcTemplate jdbc, R0reProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (properties.getDispatcher().isEnabled()) {
            return;
        }
        List<String> activeProjectIds = jdbc.queryForList(
                "SELECT id FROM projects WHERE status = 'active' ORDER BY id",
                String.class
        );
        if (activeProjectIds.isEmpty()) {
            return;
        }
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        for (String projectId : activeProjectIds) {
            stopProject(projectId, now);
        }
        LOG.info("stopped {} active project(s) on serve startup; resume from WebUI to run", activeProjectIds.size());
    }

    private void stopProject(String projectId, String now) {
        jdbc.update("""
                UPDATE projects
                SET status = 'stopped',
                    reason_worker = NULL,
                    reason_trigger = NULL,
                    reason_started_at = NULL,
                    reason_last_heartbeat_at = NULL
                WHERE id = ? AND status = 'active'
                """, projectId);
        jdbc.update("""
                UPDATE intents
                SET worker = NULL,
                    last_heartbeat_at = NULL
                WHERE project_id = ? AND concluded_at IS NULL
                """, projectId);
        jdbc.update("""
                UPDATE project_health
                SET health_status = CASE WHEN health_status = 'recovery_required' THEN 'normal' ELSE health_status END,
                    updated_at = ?
                WHERE project_id = ?
                """, now, projectId);
        jdbc.update("""
                INSERT INTO runtime_events(
                    project_id, scope_type, scope_id, worker, event_type, phase, step, message, created_at
                )
                VALUES (?, 'project', ?, 'r0re.startup', 'project_startup_stopped', 'startup', 'serve_start',
                        'serve startup converted persisted active state to stopped; resume from WebUI to run', ?)
                """, projectId, projectId, now);
    }
}
