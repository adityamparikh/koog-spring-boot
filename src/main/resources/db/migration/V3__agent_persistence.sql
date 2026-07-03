-- Step 5 (FR-13, AC-17): durable state for the Koog-native agent persistence.
--
-- Three tables, all owned by Flyway (the project convention: `ddl-auto` is off, Flyway is the
-- single schema authority). The first two mirror, byte-for-byte, the DDL that Koog's own
-- Postgres schema-migrators emit for its 1.0.0 JDBC providers, so the providers read/write these
-- tables without ever running their own `migrate()`:
--   • chat_history      ← ai.koog...PostgresJdbcChatHistorySchemaMigrator      (ChatMemory transcript)
--   • agent_checkpoints ← ai.koog...PostgresJdbcPersistenceSchemaMigrator      (Persistence checkpoints)
-- The third (pending_interaction) is app-owned: the confirm-gate's staged transfer / clarification,
-- which Koog has no construct for (ChatMemory stores messages; Persistence tombstones completed
-- runs) — so cross-turn HITL state lives here and survives restarts (AC-18).

-- Koog ChatMemory transcript store (keyed by conversation id = the run's sessionId).
CREATE TABLE IF NOT EXISTS chat_history (
    conversation_id VARCHAR(255) NOT NULL,
    messages_json   TEXT         NOT NULL,
    updated_at      BIGINT       NOT NULL,
    ttl_timestamp   BIGINT       NULL,
    CONSTRAINT chat_history_pkey PRIMARY KEY (conversation_id)
);
CREATE INDEX IF NOT EXISTS idx_chat_history_updated_at ON chat_history (updated_at);
CREATE INDEX IF NOT EXISTS idx_chat_history_ttl_timestamp ON chat_history (ttl_timestamp);

-- Koog Persistence checkpoint store (intra-run crash recovery; completed runs tombstone).
CREATE TABLE IF NOT EXISTS agent_checkpoints (
    persistence_id  VARCHAR(255) NOT NULL,
    checkpoint_id   VARCHAR(255) NOT NULL,
    created_at      BIGINT       NOT NULL,
    checkpoint_json TEXT         NOT NULL,
    ttl_timestamp   BIGINT       NULL,
    version         BIGINT       NOT NULL,
    CONSTRAINT agent_checkpoints_pkey PRIMARY KEY (persistence_id, checkpoint_id)
);
CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_created_at ON agent_checkpoints (created_at);
CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_ttl_timestamp ON agent_checkpoints (ttl_timestamp);
CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_persistence_id_created_at ON agent_checkpoints (persistence_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_checkpoints_persistence_id_version ON agent_checkpoints (persistence_id, version);

-- App-owned confirm-gate state: at most one pending clarification/confirmation per conversation.
-- `payload` is the JSON of the sealed PendingInteraction (kind is a discriminator inside the JSON,
-- Jackson @JsonTypeInfo). TEXT (not jsonb) to match Koog's own message/checkpoint columns and
-- because we never query into it. Idempotency comes from an atomic DELETE ... RETURNING consume,
-- so no version column is needed.
CREATE TABLE IF NOT EXISTS pending_interaction (
    conversation_id UUID        NOT NULL,
    payload         TEXT        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pending_interaction_pkey PRIMARY KEY (conversation_id)
);
CREATE INDEX IF NOT EXISTS idx_pending_interaction_updated_at ON pending_interaction (updated_at);
