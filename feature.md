# Feature: Agentic Money-Transfer Assistant (Koog "Sandwich" Build)

## Summary
Build a contrived money-transfer application as a Spring Boot service and progressively
layer Koog agentic capabilities on top of it, one "ingredient" at a time. The base
application (step 1) performs money transfers between accounts with no AI. Each
subsequent step adds one Koog capability — framework integration, tools, a custom
validation strategy, Postgres-backed checkpointing, OpenTelemetry observability,
transfer rollback, and history compression — following the JetBrains Koog "sandwich
recipe" (Devoxx Belgium 2025) and the `svtk/koog-tutorials` IntroToAIAgents tutorial.
The final step refactors the LLM layer to run through Spring AI. Every step is delivered
on its own git branch that builds on the previous branch, and work **pauses after each
step** for review and explicit go-ahead before the next begins.

## User Stories
- As a **bank customer**, I want to transfer money to a contact by describing it in
  natural language ("send $50 to Alice for lunch") so that I don't have to fill in
  account numbers manually.
- As a **bank customer**, when my recipient is ambiguous (two "Daniels"), I want the
  assistant to ask me which contact I mean so that money goes to the right person.
- As a **bank customer**, I want to confirm a transfer before it executes so that no
  money moves without my approval.
- As a **bank customer**, when I try to send more than my balance, I want the assistant
  to tell me and offer to send up to my available balance so that the transfer doesn't
  simply fail.
- As a **bank customer**, I want to undo a transfer I just made so that mistakes are
  recoverable.
- As an **operator/developer**, I want agent runs to be checkpointed, traced, and
  resumable so that I can observe, debug, and recover long-running conversations.
- As a **developer**, I want each capability isolated on its own branch with tests so
  that I can review and learn the framework incrementally.

## Delivery Strategy (branches & pauses)
Each step is a branch built on top of the previous one. After each step the assistant
**stops, shows a diff/summary of what changed, and waits for explicit approval** before
starting the next step. Each step's PR also updates **`README.md`** with a usage section
(run instructions + example requests) for the capability it introduced, so the usage guide
grows **incrementally** toward the completion deliverable (AC-27) instead of being written
all at once at the end.

| Step | Branch | Builds on | Ingredient |
|------|--------|-----------|------------|
| 1 | `step1-money-transfer` | `master`/`main` | Money-transfer domain persisted in Postgres (Spring Data JDBC + Flyway, docker-compose), no AI. Well tested. |
| 2 | `step2-koog-spring-boot` | step 1 | Koog Spring Boot starter integration. |
| 3 | `step3-tools` | step 2 | `getContacts`, `chooseRecipient`, `sendMoney` tools + event handling. |
| 4 | `step4-custom-strategy` | step 3 | Balance lookup + custom strategy: cap transfer at available balance. |
| 5 | `step5-persistence` | step 4 | Koog checkpointing persisted to the existing Postgres (in-memory provider → Postgres provider). |
| 6 | `step6-observability` | step 5 | OpenTelemetry via docker-compose. |
| 7 | `step7-rollback` | step 6 | Undo a transfer (compensating action / rollback registry). |
| 8 | `step8-history-compression` | step 7 | History compression with fact retrieval. |
| 9 | `step9-unit-integration-tests` | step 8 | Fill out unit + integration test coverage. |
| 10 | `step10-spring-ai` | step 9 | Refactor LLM layer to Spring AI integration. |

Global conventions (from `docs/project.md`): Kotlin + **Spring Boot 3.5.x** (latest
stable in that line — chosen for Koog Spring Boot starter compatibility, resolving OQ-1),
Gradle Kotlin DSL with the version catalog (`gradle/libs.versions.toml`) as the **single
source of truth for all versions and dependency coordinates** (no inline version strings
or `extra` properties in `build.gradle.kts` — resolving OQ-2), modular-by-feature packages
under `dev.aparikh.moneytransfer`, REST base path `/api/v1`, central `@RestControllerAdvice`
error handling (RFC 7807 `ProblemDetail`), constructor injection + immutable Kotlin, and
"latest and greatest" stable library versions declared in the catalog. A Swagger/OpenAPI
UI (springdoc, version compatible with Boot 3.5.x) is exposed for all REST endpoints.
**Domain data (accounts, balances, transfers, contacts) is persisted in PostgreSQL from
step 1**, using **Spring Data JDBC** with **Flyway** migrations; Postgres runs locally via
**Spring Boot Docker Compose support** (`compose.yaml`). The Koog **checkpoint/agent-state**
tables are added to the *same* Postgres database at **step 5** — so business data is durable
from step 1, while the agent's conversation state becomes durable at step 5.

**Stack realignment for Boot 3.5.x (applies at step 1):** because the current skeleton was
scaffolded for Boot 4, step 1 must realign it — Jackson 3 → Jackson 2
(`com.fasterxml.jackson.module:jackson-module-kotlin`), starter renames
(`spring-boot-starter-web`, and a single `spring-boot-starter-test` replacing the
`-webmvc`/`-webmvc-test`/`-actuator-test` starters), and the Spring Boot Gradle plugin
pinned to 3.5.x. The **Java 25 toolchain is retained** (Boot 3.5.x supports Java 25).

---

## Functional Requirements

### Step 1 — `step1-money-transfer` (no AI)

#### FR-01: Domain model (persisted aggregates)
Model, as Spring Data JDBC aggregate roots (Venmo-style — the account is the source of truth,
a contact is a thin edge):
- `Account` (id, `firstName`, `lastName?`, `phoneNumber?`, currency = USD, `balance: BigDecimal`)
  — a person's **profile + wallet** and the **single source of truth** for display info and
  balance; the `accountId` **is** the person's identity (there is no separate user table).
- `Contact` (id, ownerAccountId, `contactAccountId`, `nickname?`) — a directed **edge** in an
  owner's address book referencing another account (Venmo-"friend" style). It does **not**
  duplicate the friend's name/phone; those resolve from the linked account. A unique
  `(ownerAccountId, contactAccountId)` constraint prevents duplicate edges.
- `Transfer` (id, senderAccountId, recipientAccountId, `amount: BigDecimal`, currency USD,
  purpose, timestamp, status) — an immutable ledger row.

**Identity & terminology:** a "user"/"person" is identified by their `accountId`. Transfers
move money between accounts (`senderAccountId` → `recipientAccountId`). The agent tools work
in terms of a **contact id**, which is resolved to the recipient's account via
`Contact.contactAccountId` **before** the ledger is touched. A contact's display name/phone
are resolved from the linked account at read time (`nickname ?: account name`). Money is
`BigDecimal` mapped to SQL `NUMERIC`; currency is USD throughout. See `docs/data-model.md`
for the ER diagram and relationships.

#### FR-02: Persistence — Spring Data JDBC + Flyway
Provide `AccountRepository`, `ContactRepository`, `TransferRepository` as Spring Data JDBC
`CrudRepository`s. Define the schema via **Flyway** SQL migrations (`V1__init.sql`, …).
Seed reference data via a Flyway seed migration: accounts with names/phones/balances (incl.
two accounts named "Daniel" so name lookups are ambiguous), plus the demo user's contact
**edges** to those accounts (one carrying a nickname). PostgreSQL is provisioned locally
through **Spring Boot Docker Compose support** (`compose.yaml` defining a `postgres` service);
the app connects on startup.

#### FR-03: Transfer service (atomic + concurrency-safe)
`TransferService.transfer(senderAccountId, recipientAccountId, amount, purpose)` runs in a
single `@Transactional` unit: it validates that both accounts exist and the amount is
positive, then debits the sender, credits the recipient, records the `Transfer`, and
returns a result. Concurrent transfers against the same account must not corrupt balances —
the debit is performed with an **atomic conditional UPDATE** rather than read-modify-write:
`UPDATE account SET balance = balance - :amount WHERE id = :senderAccountId AND balance >= :amount`
(a `@Modifying` repository query returning rows-affected). **Rows-affected = 0 means
insufficient funds** → the transaction rolls back and the transfer is rejected. The
database's row lock during the UPDATE serializes concurrent debits, so no version column
or application-side retry is needed. Unknown parties are likewise rejected and never
mutate balances.

#### FR-04: Contact lookup
`ContactService.getContacts(accountId)` returns the owner account's contacts, resolved for
display (name/phone from the linked account). `ContactService.findByName(accountId, name)`
matches on the linked account's name **or** the contact nickname (joining to `account`) and
returns zero, one, or many resolved contacts (the "ambiguous recipient" case).

#### FR-05: REST API + OpenAPI
Expose under `/api/v1`: list contacts, get account/balance, create transfer, list
transfers. Errors returned as RFC 7807 `ProblemDetail`. Swagger UI available and documents
all endpoints. Because authentication is out of scope, the **acting user (`accountId`) is
supplied explicitly by the caller** — via an `X-User-Id` header (or an explicit request
field/path variable) — rather than derived from a security context.

### Step 2 — `step2-koog-spring-boot`

#### FR-06: Koog Spring Boot starter
Add `ai.koog:koog-spring-boot-starter` (latest stable) to the version catalog. Configure
**both** the Anthropic and OpenAI clients via `application.properties`
(`ai.koog.anthropic.enabled=true`, `ai.koog.anthropic.api-key=${ANTHROPIC_API_KEY}`;
`ai.koog.openai.enabled=true`, `ai.koog.openai.api-key=${OPENAI_API_KEY}`). API keys supplied
via env vars; never committed. The starter exposes `anthropicExecutor`, `openAIExecutor`, and
a combined **`multiLLMPromptExecutor`** bean. Also add `ai.koog:agents-test` as a
`testImplementation` so agent tests run against a **mock executor** (`getMockExecutor`) from
this step onward — no test hits a real LLM API.

#### FR-07: Agent service skeleton
Inject the auto-configured **`multiLLMPromptExecutor`** bean. Add an `AgentService` that
constructs an `AIAgent` over it, defaulting to **Anthropic Sonnet 5** (`AnthropicModels`) and
using **Opus 4.8** for complex turns; the model is configurable. Wrap execution in an
**automatic error-fallback**: if the Anthropic call errors or the provider is unavailable,
retry the same prompt on **OpenAI `gpt-5.4`** (`OpenAIModels`). Expose a `/api/v1/agent/chat`
endpoint that sends a user message and returns the agent's text reply **plus a
`conversationId`** for follow-up turns (see FR-10). The acting user (`accountId`) is supplied
explicitly (`X-User-Id` header / request field). No tools yet. (Exact `AnthropicModels` /
`OpenAIModels` enum names confirmed against Koog docs at implementation.)

### Step 3 — `step3-tools`

#### FR-08: Money-transfer tools
Implement a `MoneyTransferTools` class implementing Koog's `ToolSet`, exposing three
`@Tool`/`@LLMDescription`-annotated tools that delegate to the step-1 services:
- `getContacts(accountId)` — returns the acting account's contacts.
- `chooseRecipient(accountId, confusingRecipientName)` — resolves an ambiguous/unknown
  recipient by returning candidate contacts to the user for selection (see FR-10 HITL).
- `sendMoney(senderAccountId, amount, recipientContactId, purpose)` — resolves
  `recipientContactId` to its `contactAccountId`, then executes a transfer **after** user
  confirmation (see FR-10).
Register the tools in a `ToolRegistry` and attach to the agent.

#### FR-09: Event handling
Install Koog `handleEvents { … }` handlers that log/emit LLM request start
(`onLLMCallStarting`: prompt messages + available tools), LLM response
(`onLLMCallCompleted`: responses), and tool invocations (tool call started/completed
with arguments and result). Events are logged and available for inspection.

#### FR-10: Human-in-the-loop over REST (recommended design)
Replace the tutorial's console I/O with a REST multi-turn conversation:
- Agent runs are keyed by a `conversationId` (returned by `/agent/chat`, see FR-07).
- When `chooseRecipient` needs disambiguation, the agent **pauses** and the API returns a
  structured `CLARIFICATION` response listing candidate contacts (id, name, phone).
- When `sendMoney` needs approval, the agent **pauses** and the API returns a
  `CONFIRMATION` response with the transfer summary.
- The client answers via `POST /api/v1/agent/{conversationId}/reply`; the agent resumes.
- **Mechanism (Approach B):** the pause/resume is implemented with Koog's checkpoint/
  persistence feature. When a tool needs input, the agent checkpoints its state (messages,
  current node, pending-question metadata) to a **storage provider** keyed by
  `conversationId` and the run ends; `/reply` restores from the checkpoint, injects the
  answer, and continues. In steps 3–4 the provider is **in-memory**; step 5 swaps it for
  **Postgres** with no change to this seam. Agent + strategy state must be
  `kotlinx.serialization`-serializable.

### Step 4 — `step4-custom-strategy`

#### FR-11: Balance tool
Add a `getBalance(accountId)` tool/service returning that account's current available
balance — used to validate that the **sender** has sufficient funds (see FR-12).

#### FR-12: Custom validation strategy
Implement a **custom Koog strategy** so that when a requested transfer exceeds the
sender's available balance, the agent does not fail outright but instead **offers to
transfer up to the available balance** and asks the user to accept the reduced amount
(HITL confirmation). On acceptance the capped amount is transferred. The strategy's graph
is verified with Koog's `withTesting()` + `testGraph` / `assertNodes` / `assertEdges`
(proving the over-balance branch routes to the cap-and-offer path), and the flow is
exercised end-to-end with a mock executor and mocked tools.

### Step 5 — `step5-persistence`

#### FR-13: Checkpoint schema in the existing Postgres
Postgres and `compose.yaml` already exist from step 1. Add the Koog **checkpoint/agent-state**
tables to the same database via a new Flyway migration. No new datasource — the agent
checkpoint store and the domain share one PostgreSQL instance.

#### FR-14: Checkpointing
Swap the step-3 in-memory checkpoint storage provider for a **Postgres-backed** provider
(implementing Koog's storage-provider interface; schema created by a **Flyway** migration,
`ddl-auto` stays off) so agent runs are snapshotted to Postgres and a paused conversation
(FR-10) resumes after a restart. Include per-`conversationId` concurrency + reply
idempotency (versioned
checkpoints) and TTL/cleanup for abandoned conversations. Expose endpoints to list a
conversation's checkpoints and its status. The HITL interaction seam from step 3 is
unchanged — only the provider bean changes.

### Step 6 — `step6-observability`

#### FR-15: OpenTelemetry
Add OpenTelemetry tracing/metrics for agent execution, exporting via OTLP to the Grafana
**LGTM** stack (`grafana/otel-lgtm` all-in-one container: Loki, Grafana, Tempo, Mimir)
defined in docker-compose. Agent LLM calls and tool calls appear as spans in Tempo,
viewable in Grafana.

### Step 7 — `step7-rollback`

#### FR-16: Undo a transfer
Provide the ability to undo a previously executed transfer as a **domain-level compensating
reversal**: within one `@Transactional` unit, credit the original sender, debit the original
recipient (using the same atomic conditional UPDATE), append an offsetting `Transfer` ledger
row (the ledger is immutable — nothing is deleted), and mark the original transfer
`REVERSED`. The undo is **idempotent**: a transfer already `REVERSED` cannot be undone again,
and a reversal entry cannot itself be reversed. Surface it as a Koog **rollback tool** (a
forward `sendMoney` action paired with this compensating function, per the Devoxx
`RollbackToolRegistry` pattern) **and** a REST endpoint.

Note: this is distinct from Koog's *agent-state* checkpoint rollback (step 5), which rewinds
a **conversation** to an earlier checkpoint. Undoing money is a domain compensation on the
ledger; rewinding a conversation is agent bookkeeping. The two may be used together (e.g. an
agent turn that both reverses a transfer and rolls its conversation back) but are separate
mechanisms.

### Step 8 — `step8-history-compression`

#### FR-17: History compression with fact retrieval
Enable Koog history compression so long conversations are summarized into retained facts
(fact retrieval) rather than unbounded message history, keeping the agent within context
limits while preserving key transfer facts.

### Step 9 — `step9-unit-integration-tests`

#### FR-18: Test coverage
Add/round out unit tests (services, tools, custom strategy) and integration tests
(REST endpoints, agent flows, Postgres checkpointing via Testcontainers, HITL multi-turn,
rollback). Agent/tool/strategy tests use Koog's **`agents-test`** (`getMockExecutor`,
`mockTool`/`mockLLMToolCall`, `withTesting()` graph assertions); domain and end-to-end
tests use Testcontainers Postgres. Steps 1–8 include tests as they go; step 9 fills gaps
and adds cross-cutting integration coverage.

### Step 10 — `step10-spring-ai`

#### FR-19: Spring AI refactor
Refactor the LLM layer to use Koog's Spring AI integration
(`koog-spring-ai-starter-model-chat`): the agent's `PromptExecutor` is assembled from a
Spring AI `ChatModel` rather than the Koog OpenAI client directly. Agents, tools,
strategies, checkpointing, and rollback from steps 1–9 remain functionally unchanged.

---

## Acceptance Criteria

**Step 1**
- [ ] AC-01: `./gradlew build` passes with the persisted money-transfer domain, services, and REST API.
- [ ] AC-02: A transfer between two seeded accounts succeeds and **persists** updated balances (survives restart).
- [ ] AC-03: A transfer exceeding the sender's balance is rejected with a clear error and no balance change.
- [ ] AC-04: Looking up an ambiguous name (two Daniels) returns multiple candidates from the DB.
- [ ] AC-05: Swagger UI lists and exercises all `/api/v1` endpoints; errors are `ProblemDetail`.
- [ ] AC-06: Flyway migrations create the schema and seed data on a clean database; app connects via docker-compose.
- [ ] AC-07: Concurrent transfers on the same account keep balances consistent — a parallel-transfer test proves no lost updates and no overdraft via the atomic conditional UPDATE.
- [ ] AC-08: Unit + integration tests (Testcontainers Postgres) cover transfer success, insufficient funds, unknown party, ambiguous lookup, and concurrency.

**Step 2**
- [ ] AC-09: App starts with the Koog Spring Boot starter and **both** providers configured (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`); the `multiLLMPromptExecutor` bean is available.
- [ ] AC-10: `POST /api/v1/agent/chat` returns an LLM text reply (no tools yet).

**Step 3**
- [ ] AC-11: The agent can list contacts, disambiguate a recipient, and send money end-to-end via tools.
- [ ] AC-12: Ambiguous recipient yields a `CLARIFICATION` response; a reply selects the contact.
- [ ] AC-13: `sendMoney` only executes after a `CONFIRMATION` reply of "yes".
- [ ] AC-14: LLM request/response and tool-call events are logged/observable.

**Step 4**
- [ ] AC-15: `getBalance` returns the correct persisted available balance.
- [ ] AC-16: Requesting more than the balance triggers an offer to send up to the available balance; accepting transfers the capped amount.

**Step 5**
- [ ] AC-17: Koog checkpoint tables are added to the existing Postgres via a Flyway migration.
- [ ] AC-18: Agent runs are checkpointed to Postgres (provider swapped from in-memory); a paused conversation resumes after an app restart.

**Step 6**
- [ ] AC-19: OpenTelemetry spans for agent LLM calls and tool calls are exported via OTLP to the LGTM stack and visible in Grafana/Tempo.

**Step 7**
- [ ] AC-20: A completed transfer can be undone via a compensating reversal; balances return to their pre-transfer values, an offsetting ledger row is appended, and the original transfer is marked `REVERSED`. Undoing an already-reversed transfer is rejected (idempotent).

**Step 8**
- [ ] AC-21: A long conversation is compressed to retained facts; the agent still answers correctly using retrieved facts.

**Step 9**
- [ ] AC-22: Unit + integration tests (incl. Testcontainers Postgres) pass in `./gradlew build`; meaningful coverage of services, tools, strategy, HITL, checkpointing, and rollback.

**Step 10**
- [ ] AC-23: The agent runs via Spring AI's `ChatModel` (Koog Spring AI starter); all prior acceptance criteria still pass.

**Global**
- [ ] AC-24: Each step is on its own branch built atop the previous; work pauses after each step for review and explicit approval.
- [ ] AC-25: Every library version is declared in `gradle/libs.versions.toml` at the latest stable compatible version.
- [ ] AC-26: All Koog-related code (from step 2 on) carries teaching comments — KDoc explaining the concept, inline `//` comments at each Koog call site, and a `docs.koog.ai` link — at every Koog usage site (agents, tools, events, strategy, checkpointing, observability, rollback, compression, Spring AI).
- [ ] AC-27: The `README.md` usage guide is built **incrementally** — step 1 seeds it with the money-transfer REST usage, and each step 2–10 adds a section for its capability — and after step 10 it coherently documents how to run the app and walk through every scenario (happy-path transfer, ambiguous recipient, insufficient-balance cap-and-offer, undo/rollback, checkpoint resume, observability) with concrete example requests.
- [ ] AC-28: Multi-LLM fallback — the agent defaults to Anthropic Sonnet 5 (Opus 4.8 for complex turns); when the Anthropic client errors, the same prompt completes via OpenAI `gpt-5.4` (verified with an `agents-test` mock that fails the Anthropic client).
- [ ] AC-29: Every step from 2 onward updates `README.md` with a usage section (run instructions + example requests) for the capability it added.

## Technical Scope

### Affected / new modules (under `dev.aparikh.moneytransfer`)
- `account` — `Account` aggregate, Spring Data JDBC repository, balance service.
- `contact` — `Contact` aggregate, repository, lookup service.
- `transfer` — `Transfer` aggregate, repository, `TransferService` (transactional, atomic
  conditional UPDATE), compensating reversal / rollback (step 7).
- `agent` — `AgentService`, Koog agent config, `MoneyTransferTools` (ToolSet), event
  handlers, custom strategy (step 4), conversation/session store, checkpointing (step 5),
  history compression (step 8), Spring AI wiring (step 10).
- `web` / per-feature controllers — REST endpoints, `@RestControllerAdvice`, OpenAPI config.
- `config` — Koog, OpenTelemetry, persistence/datasource configuration.

### New components (indicative)
- Persistence: Spring Data JDBC `CrudRepository`s (`AccountRepository`, `ContactRepository`,
  `TransferRepository`); Flyway migrations (`db/migration/V*.sql`) for schema + seed +
  (step 5) checkpoint tables.
- REST: `TransferController`, `ContactController`, `AccountController`, `AgentController`
  (`/agent/chat`, `/agent/{conversationId}/reply`, checkpoints, rollback).
- Koog: `MoneyTransferTools`, `ToolRegistry`, event handlers, custom strategy,
  checkpoint storage provider (in-memory → Postgres), rollback registry,
  history-compression config.
- Infra: `compose.yaml` (Postgres from step 1; `grafana/otel-lgtm` from step 6), springdoc OpenAPI.

### Integration points
- PostgreSQL (Spring Data JDBC + Flyway) → domain data (step 1) and Koog checkpoints (step 5),
  one shared database.
- Koog Spring Boot starter → OpenAI (steps 2–9), Spring AI `ChatModel` (step 10).
- Spring Boot Docker Compose → Postgres (step 1), LGTM stack (step 6).
- OpenTelemetry (OTLP) → `grafana/otel-lgtm`.
- Testcontainers → Postgres (integration tests from step 1 onward).
- Koog `agents-test` → deterministic agent/tool/strategy tests (from step 2 onward).

## Non-Functional Requirements
- **Security:** `ANTHROPIC_API_KEY` and `OPENAI_API_KEY` (and any DB creds) come from env/config, never committed.
  Money-moving endpoints validate inputs; note that authentication is not yet in scope
  (flagged in `docs/project.md`).
- **Correctness:** money uses `BigDecimal` mapped to SQL `NUMERIC`; no floating-point money
  math. Transfers are `@Transactional` and concurrency-safe via an atomic conditional
  `UPDATE … WHERE balance >= :amount` (no read-modify-write); balances never go negative;
  the transfer ledger is durable.
- **Observability:** step 6 onward, agent runs are traceable end-to-end.
- **Testability:** each step ships tests; step 1 is "well tested". Agent behaviour is tested
  **deterministically, without live LLM calls**, using Koog's testing framework
  (`ai.koog:agents-test`): `getMockExecutor` / `mockLLMAnswer` to stub LLM responses,
  `mockTool` / `mockLLMToolCall` to stub tool calls, and `withTesting()` +
  `testGraph` / `assertNodes` / `assertEdges` to assert strategy-graph topology and node/edge
  behaviour. Domain and persistence tests use Testcontainers Postgres.
- **Reproducibility:** all versions pinned in the version catalog; docker-compose brings up
  infra locally.
- **Documentation (Koog teaching comments):** because this is a progressive, tutorial-style
  build, **every place Koog APIs are used** — agent construction, tool definitions
  (`@Tool`/`@LLMDescription`/`ToolSet`), event handlers (`handleEvents`), the custom
  strategy graph, checkpoint/persistence wiring, OpenTelemetry, the rollback registry,
  history compression, and the Spring AI integration — carries explanatory comments:
  **KDoc** on the class/function stating the Koog concept and *why* it's used, **inline
  `//` comments** at each Koog call site explaining that specific step, and a **link to the
  relevant `docs.koog.ai` page**. Applies from **step 2 onward**; ordinary Spring/domain
  code keeps normal KDoc (no need for step-by-step inline commentary).
- **Usage guide (final deliverable):** upon completion of the entire application (after
  step 10), the repo ships a **README usage guide** showing how to run the app (docker-compose
  up, env vars, Swagger UI) and **walk through each scenario** end-to-end: happy-path
  transfer, ambiguous-recipient disambiguation, insufficient-balance cap-and-offer, transfer
  undo/rollback, paused-conversation checkpoint resume, and where to see traces in the LGTM
  observability stack. Include concrete example requests (curl / Swagger) for each.

## Out of Scope
- Authentication/authorization and multi-tenant security.
- Real banking integrations, real payment rails, or real currency conversion.
- Multi-currency support / FX — all balances and transfers are USD.
- Transfer idempotency / duplicate-submit protection — a deliberate simplification: a
  repeated create-transfer request posts a **new** `Transfer`; there is no idempotency key.
  (Undo, by contrast, *is* idempotent — see FR-16.)
- A rich custom frontend beyond Swagger UI (unless requested later).
- Production hardening (rate limiting, secrets management beyond env vars, HA, DB failover).

## Resolved Decisions
- **OQ-1 → RESOLVED:** Target the **latest Spring Boot 3.5.x** (not Boot 4) so the Koog
  Spring Boot starter is supported. Step 1 realigns the Boot-4 skeleton accordingly
  (Jackson 2, `spring-boot-starter-web`/`-test`, 3.5.x Gradle plugin).
- **OQ-2 → RESOLVED:** The version catalog (`gradle/libs.versions.toml`) is the **single
  source of truth** — all versions and coordinates live there; remove inline version
  strings and the `koogVersion` `extra` from `build.gradle.kts`. springdoc-openapi pinned
  to the latest version compatible with Boot 3.5.x.

- **OQ-3 → RESOLVED:** Retain the **Java 25** toolchain — Spring Boot 3.5.x supports Java 25.
- **OQ-4 → RESOLVED (updated):** Use Koog's **`MultiLLMPromptExecutor`** with **Anthropic
  Sonnet 5** as the default model and **Opus 4.8** for complex turns (e.g. the step-4
  strategy), plus an **automatic error-fallback to OpenAI `gpt-5.4`** (retry wrapper: try
  Anthropic → on error/unavailable, retry the same prompt on OpenAI). Requires both
  `ANTHROPIC_API_KEY` and `OPENAI_API_KEY`. Models configurable.
- **OQ-5 → RESOLVED:** Step 6 exports via **OpenTelemetry (OTLP)** to the Grafana
  **LGTM** stack (`grafana/otel-lgtm` all-in-one container: Loki, Grafana, Tempo, Mimir)
  in docker-compose.

- **OQ-6 → RESOLVED (Approach B):** Install Koog's checkpoint/persistence feature at
  **step 3** behind an **in-memory storage provider**; at **step 5** swap the provider to a
  **Postgres-backed** one — no change to the HITL interaction seam. Agent and custom-strategy
  state must be `kotlinx.serialization`-serializable from step 3 onward. This pre-wires step 7
  rollback (undo restores/compensates from the same checkpoints). Exact Koog persistence API
  names to be confirmed against Koog docs (via Context7) when planning steps 3/5.
- **OQ-7 → RESOLVED (persist domain data):** Accounts, balances, transfers, and contacts are
  persisted in **PostgreSQL from step 1** using **Spring Data JDBC + Flyway**, with Postgres
  run locally via **Spring Boot Docker Compose support**. Concurrency safety via an **atomic
  conditional `UPDATE … WHERE balance >= :amount`** (no `@Version`, no read-modify-write).
  Step 5 adds Koog checkpoint tables to the *same* database — so business data is durable
  from step 1, agent conversation state from step 5.
- **OQ-8 → RESOLVED (identity model, Venmo-style):** A person is identified by their
  `accountId` (no separate user table); the **`Account` is the single source of truth** for
  profile (`firstName`, `lastName?`, `phoneNumber?`) and balance. A **`Contact` is a thin edge**
  `(ownerAccountId, contactAccountId, nickname?)` referencing another account — it does **not**
  duplicate the friend's name/phone (those resolve from the linked account). The agent's
  `recipientContactId` is resolved to the recipient's account via `contactAccountId` before any
  ledger write. Transfers move money `senderAccountId` → `recipientAccountId`. See
  `docs/data-model.md` for the ER diagram. (Refined from an earlier saved-payee model that
  stored name/phone on the contact + a `linkedAccountId`.)
- **OQ-9 → RESOLVED (caller identity):** Authentication is out of scope, so the acting user
  (`accountId`) is passed **explicitly** by the caller — an `X-User-Id` header (or explicit
  request field/path variable) — not derived from a security context.

## Open Questions
- _None outstanding._ All decisions resolved; remaining specifics (exact Koog persistence
  API names, OTLP wiring details) are confirmed at their respective steps during planning.

---

## Revision History

| Date | Change Summary |
|------|----------------|
| 2026-07-01 | Initial spec: 10-step agentic money-transfer "sandwich" build. |
| 2026-07-01 | Resolved OQ-1..OQ-7 (Boot 3.5.x, catalog SoT, Java 25, gpt-5.4, OTel LGTM, HITL Approach B, persist domain data); switched concurrency to atomic conditional UPDATE. |
| 2026-07-01 | Refinement — closed loose ends: defined identity model (OQ-8) & explicit caller identity (OQ-9); made FR IDs contiguous (FR-01–19); clarified conversation-id return; reworked undo (FR-16) as idempotent domain compensation; removed stale locking/`ddl-auto` wording; noted transfer idempotency as out of scope. |
| 2026-07-01 | Added Koog testing framework (`ai.koog:agents-test`) to the spec: NFR testability, step-2 dependency, step-4 strategy graph assertions, and step-9 coverage. |
| 2026-07-01 | Added convention: Koog-related code (step 2 on) must carry teaching comments — KDoc + inline step comments + `docs.koog.ai` links (NFR + AC-26). |
| 2026-07-01 | Added final deliverable: README usage guide with runnable scenario walkthroughs on completion (NFR + AC-27). |
| 2026-07-01 | Switched LLM setup to `MultiLLMPromptExecutor`: Anthropic Sonnet 5 default + Opus 4.8 for hard calls, automatic error-fallback to OpenAI `gpt-5.4` (updates OQ-4, FR-06/07, AC-09; adds AC-28). |
| 2026-07-01 | Adopted per-step README pattern: each step's PR adds a README usage section for its capability, growing incrementally toward AC-27 (Delivery Strategy + AC-27 reworded + AC-29). |
| 2026-07-01 | Refined domain to Venmo-style (OQ-8, FR-01/02/04/08): `Account` is profile+wallet source of truth; `Contact` is a thin edge (`linkedAccountId` → `contactAccountId`, + `nickname`), no duplicated name/phone. Added `docs/data-model.md` (ER diagram). |
