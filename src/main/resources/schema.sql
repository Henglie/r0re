CREATE TABLE IF NOT EXISTS settings (
    intent_timeout INTEGER NOT NULL DEFAULT 15,
    reason_timeout INTEGER NOT NULL DEFAULT 1800,
    workspace_sync_enabled INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS projects (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'active',
    bootstrap_enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    reason_worker TEXT,
    reason_trigger TEXT,
    reason_started_at TEXT,
    reason_last_heartbeat_at TEXT
);

CREATE TABLE IF NOT EXISTS facts (
    id TEXT NOT NULL,
    project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    description TEXT NOT NULL,
    kind TEXT NOT NULL DEFAULT 'fact',
    verified INTEGER NOT NULL DEFAULT 0,
    creator TEXT,
    PRIMARY KEY (id, project_id)
);

CREATE TABLE IF NOT EXISTS intents (
    id TEXT NOT NULL,
    project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    to_fact_id TEXT,
    description TEXT NOT NULL,
    creator TEXT NOT NULL,
    worker TEXT,
    last_heartbeat_at TEXT,
    created_at TEXT NOT NULL,
    concluded_at TEXT,
    PRIMARY KEY (id, project_id)
);

CREATE TABLE IF NOT EXISTS intent_sources (
    intent_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    fact_id TEXT NOT NULL,
    PRIMARY KEY (intent_id, project_id, fact_id),
    FOREIGN KEY (intent_id, project_id) REFERENCES intents(id, project_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS hints (
    id TEXT NOT NULL,
    project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    creator TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (id, project_id)
);

CREATE TABLE IF NOT EXISTS counters (
    name TEXT PRIMARY KEY,
    value INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS scoped_counters (
    project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    value INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id, kind)
);

CREATE TABLE IF NOT EXISTS runtime_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    scope_type TEXT NOT NULL,
    scope_id TEXT,
    worker TEXT,
    event_type TEXT NOT NULL,
    phase TEXT,
    step TEXT,
    message TEXT,
    artifact TEXT,
    tool TEXT,
    progress_current INTEGER,
    progress_total INTEGER,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS completion_candidates (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    source_intent_id TEXT,
    worker TEXT NOT NULL,
    candidate_type TEXT NOT NULL,
    candidate_value TEXT NOT NULL,
    evidence_summary TEXT,
    artifact_path TEXT,
    verification_status TEXT NOT NULL DEFAULT 'pending',
    verifier_worker TEXT,
    verified_at TEXT,
    created_at TEXT NOT NULL
);

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
);

CREATE TABLE IF NOT EXISTS project_health (
    project_id TEXT PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    health_status TEXT NOT NULL DEFAULT 'normal',
    completion_status TEXT NOT NULL DEFAULT 'none',
    active_dispatch_profile TEXT,
    quarantine_reason TEXT,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS automation_jobs (
    id TEXT PRIMARY KEY,
    job_type TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    schedule TEXT NOT NULL,
    payload TEXT,
    last_run_at TEXT,
    last_status TEXT,
    last_message TEXT
);

INSERT OR IGNORE INTO settings(rowid, intent_timeout, reason_timeout, workspace_sync_enabled) VALUES (1, 15, 1800, 1);
INSERT OR IGNORE INTO counters(name, value) VALUES ('project', 0);
INSERT OR IGNORE INTO project_health(project_id, health_status, completion_status, updated_at)
SELECT id,
       'normal',
       CASE WHEN status = 'completed' THEN 'finalized' ELSE 'none' END,
       created_at
FROM projects;
INSERT OR IGNORE INTO verification_intents(id, project_id, candidate_id, status, worker, prompt_group, tool, summary, artifact, created_at, updated_at)
SELECT 'v-' || id, project_id, id, verification_status, verifier_worker, NULL, NULL, NULL, artifact_path, created_at, COALESCE(verified_at, created_at)
FROM completion_candidates;
INSERT OR IGNORE INTO automation_jobs(id, job_type, enabled, schedule) VALUES ('scan_conflicting_completions', 'scan_conflicting_completions', 1, 'every:300');
INSERT OR IGNORE INTO automation_jobs(id, job_type, enabled, schedule) VALUES ('scan_mock_pollution', 'scan_mock_pollution', 1, 'every:300');
INSERT OR IGNORE INTO automation_jobs(id, job_type, enabled, schedule) VALUES ('scan_stuck_candidate_pending', 'scan_stuck_candidate_pending', 1, 'every:180');
INSERT OR IGNORE INTO automation_jobs(id, job_type, enabled, schedule) VALUES ('retry_verifier_retryable_failed', 'retry_verifier_retryable_failed', 1, 'every:180');
INSERT OR IGNORE INTO automation_jobs(id, job_type, enabled, schedule) VALUES ('scan_dispatcher_health', 'scan_dispatcher_health', 1, 'every:120');
INSERT OR IGNORE INTO automation_jobs(id, job_type, enabled, schedule) VALUES ('scan_runtime_schema_mismatch', 'scan_runtime_schema_mismatch', 1, 'every:600');
