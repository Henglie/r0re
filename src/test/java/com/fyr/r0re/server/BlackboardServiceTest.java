package com.fyr.r0re.server;

import com.fyr.r0re.protocol.Models.BlackboardFactRequest;
import com.fyr.r0re.protocol.Models.BlackboardView;
import com.fyr.r0re.protocol.Models.CreateIntentRequest;
import com.fyr.r0re.protocol.Models.CreateProjectRequest;
import com.fyr.r0re.protocol.Models.Fact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.JDBC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackboardServiceTest {
    private Path dbPath;

    @AfterEach
    void tearDown() throws Exception {
        if (dbPath != null) {
            Files.deleteIfExists(dbPath);
        }
    }

    @Test
    void blackboardAddFactDeduplicatesNormalizedDescription() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);
        String projectId = service.createProject(new CreateProjectRequest(
                "demo", "origin", "goal", true, List.of()
        )).project().id();

        Fact first = service.blackboardAddFact(projectId, new BlackboardFactRequest("pi_a", "Native check OK", true, null));
        Fact duplicate = service.blackboardAddFact(projectId, new BlackboardFactRequest("pi_b", "  native   check ok  ", null, null));

        assertEquals(first.id(), duplicate.id());
        assertEquals("pi_a", duplicate.creator());
        assertTrue(duplicate.verified());
        long factCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM facts WHERE project_id = ? AND kind = 'fact'", Long.class, projectId);
        // origin + goal + one blackboard fact
        assertEquals(3, factCount);
        long eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runtime_events WHERE project_id = ? AND event_type = 'blackboard_fact'", Long.class, projectId);
        assertEquals(1, eventCount);
    }

    @Test
    void blackboardAddDeadEndForcesUnverifiedAndDeduplicatesWithinKind() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);
        String projectId = service.createProject(new CreateProjectRequest(
                "demo", "origin", "goal", true, List.of()
        )).project().id();

        Fact fact = service.blackboardAddFact(projectId, new BlackboardFactRequest("pi_a", "wrong branch", null, null));
        Fact deadEnd = service.blackboardAddDeadEnd(projectId, new BlackboardFactRequest("pi_a", "wrong branch", true, "i001"));
        Fact deadEndDuplicate = service.blackboardAddDeadEnd(projectId, new BlackboardFactRequest("pi_b", "WRONG   BRANCH", null, null));

        assertFalse(fact.id().equals(deadEnd.id()));
        assertEquals("dead_end", deadEnd.kind());
        assertFalse(deadEnd.verified());
        assertEquals(deadEnd.id(), deadEndDuplicate.id());

        BlackboardView view = service.blackboardView(projectId);
        assertTrue(view.facts().stream().anyMatch(f -> f.id().equals(fact.id())));
        assertTrue(view.facts().stream().noneMatch(f -> f.id().equals(deadEnd.id())));
        assertEquals(1, view.dead_ends().size());
        assertEquals(deadEnd.id(), view.dead_ends().get(0).id());
        long eventCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM runtime_events
                WHERE project_id = ? AND event_type = 'blackboard_deadend' AND scope_type = 'intent' AND scope_id = 'i001'
                """, Long.class, projectId);
        assertEquals(1, eventCount);
    }

    @Test
    void blackboardViewClassifiesOpenAndClaimedIntents() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);
        String projectId = service.createProject(new CreateProjectRequest(
                "demo", "origin", "goal", true, List.of()
        )).project().id();
        service.createIntent(projectId, new CreateIntentRequest(List.of("origin"), "open intent", "reason", null));
        service.createIntent(projectId, new CreateIntentRequest(List.of("origin"), "claimed intent", "pi_a", "pi_a"));

        BlackboardView view = service.blackboardView(projectId);

        assertEquals(1, view.open_intents().size());
        assertEquals("open intent", view.open_intents().get(0).description());
        assertEquals(1, view.claimed_intents().size());
        assertEquals("claimed intent", view.claimed_intents().get(0).description());
        assertEquals("pi_a", view.claimed_intents().get(0).worker());
    }

    private JdbcTemplate jdbc() throws Exception {
        dbPath = Files.createTempFile("r0re-blackboard-test-", ".sqlite");
        String url = "jdbc:sqlite:" + dbPath;
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new JDBC(), url);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        return new JdbcTemplate(dataSource);
    }
}
