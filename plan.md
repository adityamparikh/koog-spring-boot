# Implementation Plan: Money-Transfer Tools + HITL (Step 3 — `step3-tools`)

> Scope: **Step 3 only** of `feature.md` — give the step-2 agent **tools** that call the
> step-1 domain services, **event logging**, and a **human-in-the-loop (HITL)** flow
> (ambiguous-recipient clarification + send-money confirmation), modelled as plain
> **multi-turn conversation**. Branch `step3-tools` cut from `step2-koog-spring-boot`.
>
> Covers FR-08, FR-09, FR-10 and AC-11, AC-12, AC-13, AC-14, plus cross-cutting AC-25/26/29.

## Pre-implementation research (done — verified against Koog 1.0.0 source)
- **Tools:** `ToolSet` (marker interface, `ai.koog.agents.core.tools.reflect`, JVM-only
  reflection), `@Tool` + `@LLMDescription` (`ai.koog.agents.core.tools.annotations`). Build a
  registry with `ToolRegistry { tools(myToolSet.asTools()) }` and pass it as the `AIAgent`
  `toolRegistry` arg. `singleRunStrategy()` is the correct (and default) multi-step
  tool-calling loop (LLM → run tools → feed results back → repeat until a text answer).
- **Events:** `handleEvents { … }` from `ai.koog.agents.features.eventHandler.feature` inside
  the `AIAgent(...) { }` feature lambda. Real 1.0.0 callbacks: `onLLMCallStarting`,
  `onLLMCallCompleted`, `onToolCallStarting`, `onToolCallCompleted`, `onToolCallFailed`.
- **Dependencies:** the Koog Spring Boot starter already `api`-exports `agents-tools` and
  `agents-features-event-handler` transitively — **no new Koog dependency is required**.

## Key design decision — HITL is multi-turn conversation, not run-pausing
FR-10's wording ("the agent checkpoints its state and the run ends; `/reply` restores and
continues") reads as pausing an **in-flight** run — but that isn't needed, and Koog 1.0.0 has
no mid-tool-pause primitive anyway. Each `/agent/chat` call is **one complete agent run**; a
run that ends by asking "which Daniel?" *is* the pause. The user's next message is a **new
run** replaying the transcript (the step-2 `ConversationStore` already carries it). So:

- **No checkpoint/persistence feature at step 3.** The conversation transcript is the state.
  Checkpoint/persistence earns its keep only at **step 5**, for a *different* reason —
  surviving an app **restart** mid-conversation (AC-18) — not for HITL. This is a deliberate,
  documented deviation from FR-10's literal "install checkpoint at step 3": it isn't
  load-bearing until step 5, so installing it now would be dead weight. The seam that step 5
  makes durable is `conversationId`-keyed conversation state, which already exists.
- **The one thing that needs real care is not pausing — it's not letting the LLM move money
  on a whim.** `prepareTransfer` must **stage** a transfer and never execute until the user affirms.
  That is a confirm-gate (`conversationId → staged transfer`); the actual
  `TransferService.transfer(...)` runs **app-side** when the user affirms, never by the LLM
  inside a tool call.

### `/reply` resolution semantics (resolves plan ambiguities)
`/reply` behaves differently by the pending interaction's type, because the two need different
state:
- **Clarification reply** (e.g. "Craig") → **re-run the agent** with the selection appended to
  the transcript. The original intent (amount, purpose from "send €50 to Daniel") lives **only
  in the conversation**, not in any tool state — `chooseRecipient(name)` never saw the amount —
  so the LLM must re-read the history to call `prepareTransfer(chosenContactId, 50)`. This typically
  yields a `CONFIRMATION` next.
- **Confirmation reply** (e.g. "yeah, go ahead") → **app-side, no full agent run**. Interpret
  the answer as affirm / deny / unclear (see below); **affirm** → execute the staged transfer
  directly; **deny** → discard; **unclear** → re-prompt with the transfer summary (money never
  moves on an ambiguous answer). This is what makes AC-13 hold regardless of LLM behaviour.

### Natural-language yes/no interpretation
Confirmation answers are free-form natural language — "yes", "yeah", "go ahead", "proceed",
"approved", "do it" all mean **affirm**; "no", "nope", "cancel", "stop", "never mind" mean
**deny**. `AffirmationInterpreter` is **pure pattern matching (no LLM call)** — deterministic,
free, and zero-latency on the money path:
- Curated affirmative/negative phrase regexes (word-boundary anchored, so "no" doesn't match
  "now"); a reply matching **both** ("no wait, yes") or **neither** resolves to `UNCLEAR`.
- **Safety rail:** only `AFFIRM` executes; `UNCLEAR` → re-prompt, so money never moves on an
  ambiguous reply. Fully deterministic → AC-13 tests need no LLM at all.
- **Trade-off:** the long tail of genuinely unusual phrasings falls to `UNCLEAR` rather than
  being understood (acceptable for a yes/no gate; re-prompting once is cheap and safe). An LLM
  classifier was considered and dropped for simplicity + determinism on the money path.

## Architecture Decisions
- **`agent/MoneyTransferTools.kt`** — a `ToolSet` whose `@Tool @LLMDescription` methods delegate
  to `ContactService`/`TransferService`. Tools use LLM-describable primitive/String types.
- **Acting `accountId` and `conversationId` are NOT LLM arguments** — a tool that let the model
  pass an arbitrary sender account would be an injection risk. They must reach the tools in a
  **coroutine-safe** way: a `ThreadLocal` would not survive Koog dispatching tool execution
  across coroutine threads, so **`MoneyTransferTools` and its `ToolRegistry` are built
  per-request**, capturing `accountId` + `conversationId` in the instance (cheap; the tools
  still delegate to the singleton Spring services). The LLM only supplies business args
  (name, amount, purpose, contact id).
- **Confirm-gated `prepareTransfer`:** first call **never** transfers — it resolves
  `recipientContactId → contactAccountId`, records a `StagedTransfer` in `PendingInteractionStore`
  keyed by `conversationId`, and returns "confirmation required". The transfer executes only
  when the user affirms on `/reply`, fired **app-side** (deterministic — AC-13); a denial
  discards it.
- **`chooseRecipient(name)`** delegates to `ContactService.findByName`; exactly one match →
  return it; zero/many → record a `Clarification(candidates)` and return "clarification
  required" (AC-12).
- **Tool results expose ids.** `getContacts` and `chooseRecipient` return a compact line per
  contact **including `contactId`** (and display name/phone), because `prepareTransfer` takes a
  `recipientContactId` — the LLM needs the id from an earlier tool result to chain into
  `prepareTransfer`.
- **Edge rules:** at most **one** pending interaction per conversation (**last-write-wins**
  within a run); a fresh `POST /agent/chat` while a confirmation is pending **discards the
  stale staged transfer** (starts a new intent), so a forgotten "yes" can never fire a
  later transfer; `/reply` with no pending interaction (unknown/expired) → 404/409.
- **`PendingInteractionStore`** — in-memory `ConcurrentHashMap<UUID, PendingInteraction>` where
  `PendingInteraction` is a sealed type `Clarification(candidates)` | `Confirmation(stagedTransfer)`.
  This is the only new state; `ConversationStore` (step 2) still carries the transcript.
- **Tagged responses:** `/agent/chat` and `/agent/{conversationId}/reply` return
  `type: ANSWER | CLARIFICATION | CONFIRMATION` + `reply` + `conversationId`, plus `candidates?`
  (clarification) or `transferSummary?` (confirmation).
- **Agent construction (in `AgentService`):** keep the step-2 multi-LLM fallback loop
  (`llms` list over `multiLLMPromptExecutor`); each attempt now builds an
  `AIAgent(promptExecutor, llmModel, toolRegistry = registry, systemPrompt) { handleEvents { … } }`
  with `singleRunStrategy()` (default). `ContactService` gains
  `getContact(ownerAccountId, contactId): ResolvedContact` (ownership-checked) so `prepareTransfer`
  can resolve the recipient account before staging.
- **Koog teaching comments (AC-26)** at every new Koog site (`ToolSet`/`@Tool`, `ToolRegistry`,
  `handleEvents`) with `docs.koog.ai` links.
- **No new dependencies** (AC-25 unaffected).

## Implementation Steps

### Step 1: Domain support (no schema change) (FR-08)
- [ ] `contact/ContactService.kt` — add `getContact(ownerAccountId, contactId): ResolvedContact`
      (throws if the contact isn't owned by that account) to resolve `contactAccountId` before a
      transfer. Reuses the repository + existing private `resolve()`.
- [ ] (Maybe) `contact/ContactRepository.kt` — a `findByIdAndOwnerAccountId` query to enforce
      ownership at the data layer.
- Files: `contact/ContactService.kt` (+ maybe `ContactRepository.kt`). No Flyway change.

### Step 2: Agent tools + HITL state (FR-08, FR-10)
- [ ] `agent/PendingInteractionStore.kt` — `ConcurrentHashMap<UUID, PendingInteraction>`;
      `sealed interface PendingInteraction { Clarification(candidates); Confirmation(staged) }`,
      `data class StagedTransfer(senderAccountId, recipientAccountId, amount, purpose, summary)`.
- [ ] `agent/MoneyTransferTools.kt` — `ToolSet` **constructed per request** with the acting
      `accountId` + `conversationId` captured as instance fields (coroutine-safe; not
      LLM-supplied). A small factory builds the tools + `ToolRegistry` for each turn:
  - `getContacts()` → `ContactService.getContacts(ctx.accountId)` → compact list text.
  - `chooseRecipient(name)` → `ContactService.findByName(ctx.accountId, name)`; 1 → the contact;
    0/many → record `Clarification`, return "needs clarification".
  - `prepareTransfer(recipientContactId, amount, purpose)` → `ContactService.getContact(...)` →
    record `Confirmation(StagedTransfer)`, return "needs confirmation" (does **not** transfer).
- [ ] `agent/AffirmationInterpreter.kt` — natural-language yes/no via pure phrase-regex
      matching (no LLM): `AFFIRM | DENY | UNCLEAR`; both/neither → `UNCLEAR` → re-prompt.
- Files: `MoneyTransferTools.kt`, `PendingInteractionStore.kt`, `AffirmationInterpreter.kt`,
  a per-request tools/registry factory.

### Step 3: Agent service — tools, events, fallback, confirm execution (FR-08, FR-09, FR-10)
- [ ] `agent/AgentService.kt`:
  - Build `ToolRegistry { tools(moneyTransferTools.asTools()) }` once.
  - `chat(accountId, message, conversationId)` — set `AgentContext`, build the prompt from
    `ConversationStore`, run the tool-enabled agent over the `llms` fallback loop with
    `handleEvents { … }` (FR-09) installed, then read `PendingInteractionStore` to tag the
    response `ANSWER | CLARIFICATION | CONFIRMATION`.
  - `reply(conversationId, answer)` — branch on the pending type: a `Clarification` answer
    appends the selection and **re-runs the agent** (the LLM re-derives amount/purpose from the
    transcript, typically producing a `Confirmation`); a `Confirmation` answer is interpreted by
    `AffirmationInterpreter` — **affirm** → `TransferService.transfer(...)` **app-side**, **deny**
    → discard, **unclear** → re-prompt. Returns a tagged response; appends to `ConversationStore`.
- [ ] `agent/AgentEvents.kt` (optional) — extract the `handleEvents` block with teaching
      comments (logs LLM request/response + tool call started/completed with args/result).
- Files: `agent/AgentService.kt`, optionally `agent/AgentEvents.kt`.

### Step 4: (No separate infra/adapter layer)
Spring services + the in-memory stores are the adapters; nothing extra for step 3.

### Step 5: API / presentation layer (FR-10)
- [ ] `agent/AgentController.kt` — `POST /api/v1/agent/chat` returns the tagged response; add
      `POST /api/v1/agent/{conversationId}/reply` (`{answer}` → tagged response). Suspend
      handlers (+ `asyncDispatch` in tests), as in step 2.
- [ ] DTOs: extend `ChatResponse` with `type`, `candidates?`, `transferSummary?`; add
      `ReplyRequest(answer)`.
- [ ] `common/GlobalExceptionHandler.kt` — map replying to an unknown/expired conversation
      (404/409) and any new domain errors.
- Files: `AgentController.kt` (+ DTOs), advice update.

### Step 6: Tests (FR-08/09/10; AC-11..14)
- [ ] **Tool unit tests** `MoneyTransferToolsTest` (MockK on services + a set `AgentContext`):
      `getContacts` delegates; `chooseRecipient` returns 1 vs records a clarification for
      "Daniel" (2 candidates); `prepareTransfer` records a confirmation and does **not** call
      `TransferService`.
- [ ] **Agent flow tests** `AgentToolFlowTest` (Koog `agents-test`: `getMockExecutor` +
      `mockLLMToolCall`): end-to-end list→disambiguate→send (AC-11); ambiguous recipient →
      `CLARIFICATION`, a reply selects the contact (AC-12); `prepareTransfer` transfers only after a
      `/reply "yes"` (AC-13). No live LLM.
- [ ] **Event observability** (AC-14): a test event collector asserts `onLLMCall*` and
      `onToolCall*` fired for a run that calls a tool.
- [ ] **Controller** `AgentControllerTest` — `/chat` and `/reply` return the right `type` +
      payload (mocked `AgentService`, async dispatch as in step 2).
- Files: `src/test/kotlin/dev/aparikh/moneytransfer/agent/**`.

### Step 7: Documentation (AC-26, AC-29)
- [ ] Koog teaching comments at every new Koog site (tools, registry, events).
- [ ] `README.md` — add "Step 3: Agent tools & human-in-the-loop": the
      list→disambiguate→confirm→send walkthrough with `curl` for `/chat` and `/reply`, showing
      a `CLARIFICATION` (two Daniels) and a `CONFIRMATION` (send-money) round-trip.

## Acceptance Criteria Mapping
| AC | Verified By |
|----|-------------|
| AC-11: list contacts, disambiguate, send end-to-end via tools | `AgentToolFlowTest#endToEndTransferViaTools` |
| AC-12: ambiguous recipient → CLARIFICATION; reply selects contact | `AgentToolFlowTest#ambiguousRecipientClarifies` + `MoneyTransferToolsTest#chooseRecipientAmbiguous` |
| AC-13: prepareTransfer executes only after CONFIRMATION "yes" | `AgentToolFlowTest#prepareTransferRequiresConfirmation` + `MoneyTransferToolsTest#prepareTransferDefersTransfer` |
| AC-14: LLM + tool-call events observable | `AgentToolFlowTest#eventsAreObservable` |
| AC-25: no inline versions (no new deps) | manual review of `build.gradle.kts` |
| AC-26: Koog call sites carry teaching comments | manual review |
| AC-29: README step-3 usage section | manual review of `README.md` diff |

## Risks & Mitigations
- **Guaranteeing "no money moves without affirmation" (AC-13):** → `prepareTransfer` only *stages*;
  the transfer executes **app-side** when `AffirmationInterpreter` returns `AFFIRM`, never by
  the LLM inside a tool call. `UNCLEAR` re-prompts; ambiguous NL never moves money. The pending
  store is the single source of truth for what's awaiting confirmation.
- **`accountId`/`conversationId` as tool args = injection risk:** → bound per run from the
  request via `AgentContext`; the LLM never supplies them.
- **Deferring checkpoint/persistence to step 5 (deviates from FR-10's literal wording):** →
  justified: it isn't load-bearing for step-3 HITL (multi-turn conversation suffices) and its
  restart-survival value isn't tested until AC-18 at step 5. The `conversationId`-keyed seam
  step 5 makes durable already exists.
- **`ToolSet.asTools()` is JVM reflection-based:** fine on our JVM target; keep tool params/
  returns primitive/String so they're LLM-describable.
- **Deterministic tests without a live LLM:** → `getMockExecutor` + `mockLLMToolCall`/`mockTool`
  drive the tool-calling paths; no test hits a real provider (continues step-1/2 discipline).
- **Ambiguous/again-ambiguous replies:** → `/reply` to a `Clarification` validates the answer
  maps to exactly one candidate; otherwise re-clarify rather than guess.

## Estimated Complexity
**Medium.** Two Koog features (tools + events) plus a small, deterministic HITL protocol
(`/reply`, tagged responses, a pending-confirmation store) over the existing conversation
transcript. Dropping checkpoint/persistence (deferred to step 5) removes the highest-risk
piece; what remains is a focused tools + confirm-gate + multi-turn build with mock-executor
tests.
