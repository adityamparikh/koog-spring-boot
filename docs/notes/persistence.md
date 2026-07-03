# Side note: Koog-native persistence, and the one piece that stays app-side

> Context: step 5 (`feature.md` FR-13/FR-14; AC-17/AC-18) makes the agent's conversation state
> **durable** — it survives an app restart. We did this with **Koog's built-in constructs** rather
> than a hand-rolled store. This note records what maps onto Koog, what deliberately does not, and
> two dependency gotchas that bite when you first wire the JDBC providers in.

## Three kinds of state, three mechanisms

The refactor replaced two in-memory `ConcurrentHashMap`s with **three** durable mechanisms. That's
more moving parts, not fewer — the payoff is idiomatic Koog usage and literal FR-13/14 compliance,
not simplicity. Being honest about that trade is the point of this note.

| State | Before (in-memory) | Now (durable) | Keyed by |
|-------|--------------------|---------------|----------|
| Conversation transcript | `ConversationStore` + manual `renderConversation` | Koog **`ChatMemory`** feature over `PostgresJdbcChatHistoryProvider` (`chat_history` table) | run `sessionId` |
| Run checkpoints | *(none)* | Koog **`Persistence`** feature over `PostgresJdbcPersistenceStorageProvider` (`agent_checkpoints` table) | run `sessionId` |
| Confirm-gate (staged transfer awaiting a "yes") | `PendingInteractionStore` (`ConcurrentHashMap`) | **app-side** `PendingInteractionStore` over Spring Data JDBC (`pending_interaction` table) | `conversationId` |

We pass `conversationId` as the run's `sessionId` (`agent.run(input, sessionId)`), so both Koog
features scope to the same key and pick up where the conversation left off.

### `ChatMemory` — the transcript
`ChatMemory`'s interceptors **load** prior turns at run start (injecting them into the prompt) and
**store** the updated transcript at completion. That deletes the old `renderConversation` seam
entirely — we now pass the raw user message and the feature supplies the history. `windowSize(50)`
bounds how much is kept (and pre-wires step 8's compression concern).

One wrinkle: the confirm-gate resolves **without** running the agent (a "yes" executes the transfer
app-side), so those turns never pass through ChatMemory's store interceptor. `AgentService.record`
writes them to the provider by hand so the transcript stays complete.

### `Persistence` — checkpoints, and what they're *not* for
`enableAutomaticPersistence = true` snapshots after each graph node and writes a **tombstone** on
completion. So a **finished** turn is never replayed — only a run interrupted mid-flight resumes.
This is **intra-run crash recovery**, and it satisfies FR-13/14 to the letter. Its *functional*
value here is low: our runs are cheap and side-effect-free (tools only stage; money moves app-side
after confirmation), so a lost run just re-runs harmlessly. We include it because it's the spec's
named construct and it's what a heavier agent (long tool chains, expensive steps) would need.

Note the interaction with the **cross-provider fallback loop**: if the Anthropic run fails partway
and we retry on OpenAI with the same `sessionId`, `Persistence` may resume the second run from the
first's last checkpoint. That's benign — the tools are idempotent (staging is last-write-wins, no
money moves inside the run) — but it's why the fallback stays a same-`sessionId` retry rather than
anything cleverer.

## Why the confirm-gate can't be Koog-native

The staged-transfer-awaiting-a-yes is **cross-turn HITL state**, and Koog has no construct for it:

- `ChatMemory` stores *messages*, not a pending decision carrying structured action-data.
- `Persistence` tombstones completed runs, so it can't hold "a confirmation waiting for the next
  turn" — by the time the user replies, the run that staged it is finished and tombstoned.

The only Koog-native alternative would be to stash the staged transfer in agent `storage` and
`runFromCheckpoint` to resume (FR-10's "Approach B") — which **no Koog example does**, fights the
tombstone design, and would put the irreversible money decision back inside the probabilistic agent
loop. We keep it app-side and deterministic: `prepareTransfer` stages into `pending_interaction`,
and `reply(...)` **atomically consumes** it (`DELETE … RETURNING`) so a racing double-"yes" fires
the transfer at most once (FR-14 idempotency). A paused confirmation survives a restart because the
row is in Postgres (AC-18).

## Schema ownership

Flyway owns all three tables (`V3__agent_persistence.sql`); `ddl-auto` stays off. The `chat_history`
and `agent_checkpoints` DDL is copied **verbatim** from Koog 1.0.0's own Postgres schema-migrators,
so the providers find exactly the tables/columns their SQL expects and we never call their
`migrate()`. `pending_interaction` is ours: `payload` is `TEXT` (matching Koog's own JSON columns —
we never query into it), and idempotency comes from the atomic consume, so there's no `version`
column.

## Two dependency gotchas (both cost a red build)

1. **kotlinx-serialization downgrade.** Koog 1.0.0's JDBC providers serialize `Message` via
   `kotlin.time.Instant` → `kotlinx.serialization.internal.InstantSerializer`, which only exists
   from **1.9.0**. The Spring Boot BOM pins `kotlin-serialization.version` to **1.6.3**. It
   **compiles**, then throws `NoClassDefFoundError: …InstantSerializer` the first time a provider
   serializes at runtime. Fix in `build.gradle.kts`: `extra["kotlin-serialization.version"] = "1.9.0"`
   (the dependency-management plugin honours it; `enforcedPlatform` does **not** win here).
2. **Jackson + computed getters.** `StagedTransfer.summary` is a derived `val`. Jackson serializes
   it on write but has no constructor param for it on read → `UnrecognizedPropertyException`.
   `@get:JsonIgnore` it (it's presentation, never persisted).
