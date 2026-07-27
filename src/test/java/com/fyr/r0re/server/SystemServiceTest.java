package com.fyr.r0re.server;

import com.fyr.r0re.config.R0reProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.JDBC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemServiceTest {
    private final String originalUserDir = System.getProperty("user.dir");
    private Path tempRoot;
    private Path dbPath;

    @AfterEach
    void tearDown() throws Exception {
        System.setProperty("user.dir", originalUserDir);
        if (tempRoot != null && Files.exists(tempRoot)) {
            try (var walk = Files.walk(tempRoot)) {
                walk.sorted((a, b) -> b.compareTo(a))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        });
            }
        }
    }

    @Test
    void systemStatusReportsSchemaAndLocks() throws Exception {
        JdbcTemplate jdbc = jdbc();
        R0reService service = new R0reService(jdbc);
        R0reProperties properties = new R0reProperties();
        properties.getDispatcher().setConfig("dispatch.kimi.swarm.yaml");

        Path outputDir = tempRoot.resolve("output");
        Path locksDir = outputDir.resolve("locks");
        Files.createDirectories(locksDir);
        Files.writeString(outputDir.resolve("r0re-serve.pid"), "999999\n");
        Files.writeString(locksDir.resolve("r0re-serve-8000.lock"), "pid=999999\nport=8000\n");
        Files.writeString(outputDir.resolve("r0re-dispatch.pid"), "pid=999998\n");
        Files.writeString(
                locksDir.resolve("r0re-dispatch-dispatch-kimi-swarm-yaml.lock"),
                "pid=999998\nprofile=kimi_swarm\nconfig=dispatch.kimi.swarm.yaml\n"
        );
        Files.writeString(outputDir.resolve("r0re-dispatch.log"), "dispatch log\n");
        Files.writeString(outputDir.resolve("r0re-serve.log"), "serve log\n");

        System.setProperty("user.dir", tempRoot.toString());
        SystemService systemService = new SystemService(service, properties, "datas/r0re", 8000);

        var status = systemService.systemStatus();

        assertEquals("http://127.0.0.1:8000", status.server_url());
        assertEquals("datas/r0re", status.data_dir());
        assertEquals("dispatch.kimi.swarm.yaml", status.dispatcher_config());
        assertEquals("completion-health-automation", status.runtime_schema());
        assertEquals("harness-loop.v1", status.runtime_schema_version());
        assertNotNull(status.serve());
        assertEquals("999999", status.serve().pid());
        assertFalse(status.serve().running());
        assertEquals(1, status.dispatches().size());
        assertEquals("kimi_swarm", status.dispatches().get(0).profile());
        assertEquals("dispatch.kimi.swarm.yaml", status.dispatches().get(0).config());
        assertEquals(6, status.automation_jobs().size());
        assertTrue(status.automation_jobs().stream().allMatch(job -> job.id() != null && !job.id().isBlank()));
        assertTrue(status.dependencies().stream().anyMatch(dep -> "runtime-schema".equals(dep.name())));
        assertEquals(2, status.locks().size());
        assertTrue(status.locks().stream().allMatch(lock -> lock.stale()));
    }

    private JdbcTemplate jdbc() throws Exception {
        tempRoot = Files.createTempDirectory("r0re-system-service-test-");
        Path dataDir = tempRoot.resolve("datas/r0re");
        Files.createDirectories(dataDir);
        dbPath = dataDir.resolve("r0re.db");
        String url = "jdbc:sqlite:" + dbPath;
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new JDBC(), url);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        return new JdbcTemplate(dataSource);
    }
}
