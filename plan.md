# Implementation Plan: Koog-Native Persistence (Step 5 — `step5-persistence`)

> Scope: **Step 5** of `feature.md` (FR-13, FR-14; AC-17, AC-18), refactored to use **Koog's
> built-in memory + persistence constructs** instead of hand-rolled in-memory stores:
> - **`ChatMemory`** (`PostgresJdbcChatHistoryProvider`) for the conversation transcript.
> - **`Persistence`** (`PostgresJdbcPersistenceStorageProvider`) for agent checkpoints.
> - App-side **`PendingInteractionStore` persisted to Postgres** for the confirm-gate — the one
>   piece Koog has **no construct** for. Branch `step5-persistence` off `main` (steps 1–4 merged).

## Pre-implementation research (verified against Koog 1.0.0 source)
- **ChatMemory** (`ai.koog.agents.chatMemory.feature`): transitive via the starter. Install
  `install(ChatMemory) { chatHistoryProvider = …; windowSize(N) }`. Its interceptors **load** prior
  history at strategy start (injected into the prompt via `prompt.withMessages`) and **store** at
  completion — keyed by `runId`. This *replaces* our manual `renderConversation`/`ConversationStore`.
  Koog ships **`PostgresJdbcChatHistoryProvider(dataSource, tableName="chat_history", ttlSeconds)`**
  in **`ai.koog:agents-features-chat-history-jdbc`** (NEW dep). Table `chat_history(conversation_id
  VARCHAR(255) PK, messages_json TEXT, updated_at BIGINT, ttl_timestamp BIGINT)`.
- **Persistence** (`ai.koog.agents.snapshot.feature`): feature transitive; the JDBC provider
  **`PostgresJdbcPersistenceStorageProvider(dataSource, tableName="agent_checkpoints", ttlSeconds)`**
  is in **`ai.koog:agents-features-persistence-jdbc`** (NEW dep). Table `agent_checkpoints(
  persistence_id VARCHAR(255), checkpoint_id VARCHAR(255), created_at BIGINT, checkpoint_json TEXT,
  ttl_timestamp BIGINT, version BIGINT, PK(persistence_id, checkpoint_id))`. `enableAutomaticPersistence
  = true` → checkpoint after each node + **tombstone on completion** ⇒ resumes only an *incomplete*
  run (intra-run crash recovery), **not** a finished turn.
- **Keying:** `agent.run(input, sessionId)` sets `runId = sessionId`; both features key off it. We
  pass **`sessionId = conversationId`**.
- **Confirm-gate has no Koog construct:** `ChatMemory` stores *messages*; `Persistence` tombstones
  completed runs. The staged-transfer-awaiting-a-yes is app-owned HITL state → stays app-side.

## Architecture Decisions
- **New dependencies (catalog):** `ai.koog:agents-features-chat-history-jdbc` +
  `ai.koog:agents-features-persistence-jdbc` (both `1.0.0`). Postgres driver already present.
- **Schema via Flyway (AC-17 + project convention "Flyway owns the schema"):** a single
  `V3__agent_persistence.sql` creates all three tables using Koog's **exact** DDL for `chat_history`
  and `agent_checkpoints` (so the providers find their tables; we do **not** call the providers'
  `migrate()` — Flyway owns schema, `ddl-auto` stays off), plus our own `pending_interaction` table.
- **Transcript → `ChatMemory`:** remove `ConversationStore` + `AgentService.renderConversation`.
  `AgentService.chat` becomes `agent.run(message, sessionId = conversationId)`; the feature loads/
  stores history. `windowSize` bounds context (pre-wires step 8's compression concern too).
- **Checkpoints → `Persistence`:** install in the same `AIAgent { }` lambda as ChatMemory
  (confirmed compatible in Koog's own Spring example). Provides FR-13/14 compliance + crash
  recovery; keyed by `conversationId`.
- **Confirm-gate → Postgres-backed `PendingInteractionStore`:** same interface (`get/put/clear` +
  a new atomic `consume`), backed by Spring Data JDBC. Polymorphic `Clarification`/`Confirmation`
  payload stored as JSON (Jackson converter, sealed type tagged with `@JsonTypeInfo`). Atomic
  `DELETE … RETURNING` consume gives reply idempotency; a `version` column gives optimistic locking.
- **TTL/cleanup:** Koog's JDBC providers support `ttlSeconds` for their tables; a `@Scheduled` job
  evicts abandoned `pending_interaction` rows (ours) beyond `app.agent.conversation-ttl`.
- **Status endpoint (FR-14):** `GET /api/v1/agent/{conversationId}/status` → turn count (from
  ChatMemory `load`), last activity, and the pending state.

## Implementation Steps
### Step 1: Dependencies
- [ ] `gradle/libs.versions.toml` + `build.gradle.kts` — add `agents-features-chat-history-jdbc`
      and `agents-features-persistence-jdbc` (`version.ref = koog`). Confirm the Postgres driver is
      on the runtime classpath for both.

### Step 2: Schema — Flyway `V3__agent_persistence.sql` (FR-13, AC-17)
- [ ] `chat_history` and `agent_checkpoints` with Koog's exact DDL (from the schema-migrator
      source); `pending_interaction(conversation_id UUID PK, kind TEXT, payload JSONB, version
      BIGINT DEFAULT 0, updated_at TIMESTAMPTZ DEFAULT now())`.
- Files: `src/main/resources/db/migration/V3__agent_persistence.sql`.

### Step 3: Koog memory + persistence wiring (FR-14)
- [ ] `agent/AgentPersistenceConfig.kt` — `@Bean PostgresJdbcChatHistoryProvider(dataSource)` and
      `@Bean PostgresJdbcPersistenceStorageProvider(dataSource)` (no `migrate()` — Flyway owns schema).
- [ ] `AgentService.runAgent` — install `ChatMemory { chatHistoryProvider = …; windowSize(…) }` and
      `Persistence { storage = …; enableAutomaticPersistence = true }` in the `AIAgent { }` lambda;
      call `agent.run(input, sessionId = conversationId)`.
- [ ] `AgentService.chat` — drop `renderConversation` + `ConversationStore.append`; pass the raw
      `message`. Delete `ConversationStore`.
- Files: `AgentPersistenceConfig.kt`, `AgentService.kt`; remove `ConversationStore.kt`.

### Step 4: Postgres-backed `PendingInteractionStore` (the confirm-gate)
- [ ] `agent/persistence/PendingInteractionRow.kt` + `PendingInteractionRepository` (Spring Data
      JDBC), a Jackson `@WritingConverter`/`@ReadingConverter` for the JSONB payload
      (`@JsonTypeInfo` on `PendingInteraction`), a `DELETE … RETURNING` `consume`, and a TTL delete.
- [ ] `PendingInteractionStore` — same API over the repository + `consume(id)`.
- [ ] `AgentService.reply` confirmation branch → `pending.consume(id)` (idempotent).
- Files: `agent/persistence/**`, `PendingInteractionStore.kt`, `AgentService.kt` (one call site).

### Step 5: TTL cleanup + status endpoint
- [ ] `agent/ConversationCleanup.kt` (`@Scheduled`, `@EnableScheduling`) for `pending_interaction`;
      Koog tables use their own `ttlSeconds`.
- [ ] `AgentController` — `GET /{conversationId}/status` → `ConversationStatusResponse`.
- Files: `ConversationCleanup.kt`, `AgentController.kt` (+ DTO), `application.properties`.

### Step 6: Tests (AC-17, AC-18) — Testcontainers Postgres
- [ ] **Schema** applies (every IT boot); Koog providers read/write their tables.
- [ ] **ChatMemory durability:** run a turn, then a **fresh agent/new store** (simulated restart)
      loads prior history via `PostgresJdbcChatHistoryProvider` → context carries over.
- [ ] **AC-18 confirm-gate survives restart:** seed a `Confirmation` in `pending_interaction`,
      new `AgentService` (restart), `reply "yes"` → transfer executes (balances move).
- [ ] **Idempotency:** concurrent `consume` → exactly one wins.
- [ ] **Status endpoint**; **TTL cleanup**.
- Files: `src/test/kotlin/dev/aparikh/moneytransfer/agent/persistence/**`, extend
  `AgentConfirmationIntegrationTest`.

### Step 7: Docs (AC-26/29)
- [ ] `docs/notes/persistence.md` — the Koog-native design + why the confirm-gate stays app-side;
      README step-5 section; update `docs/notes/custom-strategies.md` cross-ref.

## Acceptance Criteria Mapping
| AC | Verified By |
|----|-------------|
| AC-17: checkpoint tables via Flyway | `V3__agent_persistence.sql` (chat_history + agent_checkpoints + pending_interaction) applies in every Testcontainers boot |
| AC-18: paused conversation resumes after restart | `AgentConfirmationIntegrationTest#pausedConfirmationSurvivesRestart` (fresh store reads pending, reply executes) + `ChatMemoryDurabilityIT` (history survives) |
| FR-14 idempotency | `PendingInteractionStoreIT#concurrentConsumeExecutesOnce` |

## Risks & Mitigations
- **More moving parts (three mechanisms):** ChatMemory + Koog Persistence + app-side pending. →
  accepted trade for idiomatic Koog usage + FR-13/14 compliance (documented; this is the
  Koog-native direction you chose).
- **Flyway DDL must match Koog's provider schema exactly:** → copy the DDL verbatim from the
  1.0.0 schema-migrator source; pinned version, so it won't drift. Fallback: call the providers'
  `migrate()` at startup and skip Flyway for those two tables (deviates from AC-17's "Flyway").
- **ChatMemory changes the history seam** (agent-managed vs our manual render): → the feature's
  load/store interceptors are drop-in; verify prior turns appear via an IT before deleting
  `renderConversation`.
- **Confirm-gate can't be Koog-native:** → app-side Postgres persistence + atomic consume; the one
  irreversible action (transfer) stays deterministic and out of the agent run.
- **`agent.run(input, sessionId)` must carry conversationId** or history/checkpoints won't scope: →
  centralize the sessionId in `runAgent`; assert history scoping in the ChatMemory IT.

## Estimated Complexity
**High.** Two new Koog features + two new deps, a schema mirroring Koog's provider DDL, replacing
`ConversationStore` with agent-managed `ChatMemory`, a Postgres-backed confirm-gate with idempotent
consume, plus TTL/status and Testcontainers "restart" tests. The ChatMemory seam swap and the
JSONB pending mapping carry the most risk.
