# Implementation Plan: History Compression (Step 8 — `step8-history-compression`)

> Scope: **Step 8** of `feature.md` (FR-17; AC-21). Enable Koog history compression so long
> conversations are summarized into retained facts rather than growing unboundedly. Branch
> `step8-history-compression` off `main` (post step-7 merge).

## Overview
Today `ChatMemory`'s `windowSize(50)` (`AgentService.MEMORY_WINDOW`) is the *only* bound on a
conversation's stored transcript — a hard cutoff that silently drops the oldest messages once the
window fills. That can drop the very message that resolved an ambiguous recipient ("which Daniel")
many turns before the user references them again. Step 8 replaces blind truncation with **smart
compression**: once the transcript crosses a lower threshold, Koog's history-compression feature
runs an LLM pass that extracts a small set of named facts and replaces the old messages with a
compact summary. `windowSize(50)` stays as an outer safety net, but compression should fire well
before it does.

Koog ships this as a **ready-made strategy**, `singleRunStrategyWithHistoryCompression(config)` —
a drop-in `strategy` argument to the same `AIAgent(...)` factory `AgentService` already calls, not
a hand-authored graph. That matters given `docs/notes/custom-strategies.md`'s conclusion that a
custom `strategy { }` graph is the wrong tool for most of this app's needs: this is exactly the one
situation that note's own survey table calls out — "long game/agent loops with per-turn upkeep" —
and Koog provides the recipe itself, so adopting it is a parameter change, not new graph authorship.

## Architecture Decisions
- **`FactRetrieval` over the blunter strategies.** Koog offers `WholeHistory` (one generic prose
  TLDR), `FromLastNMessages`/`FromTimestamp`/`Chunked` (truncate, no semantic awareness), and
  `FactRetrievalHistoryCompressionStrategy` (extract named facts per `Concept`, then replace
  history with a compact assistant message containing them). FR-17 asks for "retained facts," and
  fact retrieval is the only strategy that names *what* to keep rather than *how much* — it can't
  silently drop the one fact that mattered.
- **Narrow `Concept` list, on purpose.** Unlike a typical assistant (recommendations, support
  tickets), this app's authoritative state — the ledger, pending confirmations — already lives in
  Postgres and is **re-queried live via tools** every turn (`getRecentTransfers`, `getBalance`,
  `pending_interaction`). Chat history isn't the source of truth here, just conversational
  continuity. So only two `Concept`s are defined:
  - `resolved_recipient` (`FactType.SINGLE`) — which contact an ambiguous name most recently
    resolved to, so "send them another $20" still works after compression.
  - `recent_topic` (`FactType.SINGLE`) — a brief note on what the conversation has been about, the
    general-continuity fallback a `WholeHistory` TLDR would have given.

  A longer list (user preferences, a running transfer log, etc.) was considered and rejected: the
  tools already give the model live, authoritative answers for anything ledger-related, so
  extracting more would just duplicate that with a staler, LLM-summarized copy.
- **Fallback: `FromLastNMessages(10)`, not the default `NoCompression`.** If a conversation never
  resolves an ambiguous recipient and has no clear "topic" to extract (e.g. the user only asks
  balance questions), `FactRetrieval`'s own default fallback (`NoCompression`) would leave the
  oversized history untouched until `windowSize(50)` truncates it blindly. Passing an explicit
  `FromLastNMessages(10)` fallback keeps *some* bound in that edge case instead of relying on the
  hard cutoff.
- **`retrievalModel = model` — reuse the turn's active model.** The extraction call could target a
  separate (cheaper) model, but that needs its own config property and, more importantly, would
  bypass the existing Anthropic→OpenAI fallback: a hardcoded model id could fail if only one
  provider's key is configured, defeating `AgentService`'s multi-provider design. Passing
  `turnStrategy`'s own `model` parameter — whichever provider is driving the current attempt — is
  simpler and can't desync from provider availability. Revisit only if extraction cost becomes a
  real concern.
- **Threshold below `windowSize`, not above it.** `HistoryCompressionConfig.isHistoryTooBig` is a
  `(Prompt) -> Boolean` predicate; Koog's own docs use `prompt.messages.size > 100` as the example.
  New `HistoryCompressionProperties.maxMessages` (default well under 50) drives
  `{ prompt -> prompt.messages.size > maxMessages }`, so compression is the thing that normally
  fires — `windowSize(50)` becomes a backstop a healthy conversation should rarely reach.
- **Compression firing "after each tool execution" still solves cross-turn growth.** Each `/chat`
  call is its own `agent.run()`; `ChatMemory` loads the full Postgres-stored transcript into that
  run's prompt at the start and stores the run's final prompt back at completion. Because
  `singleRunStrategyWithHistoryCompression`'s compression node runs *inside* that same run — after
  a tool call, before the next LLM turn — a compressed prompt is what gets stored back. So this
  isn't just intra-run tidying; it's what keeps the Postgres `chat_history` row bounded across
  turns. The one gap: a turn with **zero** tool calls (rare — the system prompt keeps the agent
  tool-driven) gets no chance to compress that round; `windowSize(50)` remains the backstop.
- **Fallback loop stays safe.** A retry after an Anthropic failure re-runs with the same
  `conversationId`/`sessionId`; `ChatMemory` reloads whatever was last stored (possibly already
  compressed). Idempotent either way — no different from how the fallback already tolerates a
  `Persistence` checkpoint left by a prior attempt.

## Implementation Steps

### Step 1: Config
- [ ] `HistoryCompressionProperties` (`agent/config`, prefix `app.agent.history-compression`):
      `enabled: Boolean = true` (escape hatch, matching `ObservabilityProperties`'s precedent),
      `maxMessages: Int = 20`.
- Files: `HistoryCompressionProperties.kt` (new), `application.properties`.

### Step 2: Concepts + strategy wiring (`AgentService`)
- [ ] Define the `resolved_recipient` / `recent_topic` `Concept`s.
- [ ] In `runAgent`, when `historyCompression.enabled`, pass `strategy =
      singleRunStrategyWithHistoryCompression(HistoryCompressionConfig(isHistoryTooBig = { prompt
      -> prompt.messages.size > historyCompression.maxMessages }, compressionStrategy =
      FactRetrievalHistoryCompressionStrategy(concepts, fallback =
      HistoryCompressionStrategy.FromLastNMessages(10))))` to the existing `AIAgent(...)` call —
      `ChatMemory`, `Persistence`, `OpenTelemetry`, `handleEvents` installs are unchanged.
- [ ] Teaching comment (AC-26) at the call site with the `docs.koog.ai/history-compression` link.
- Files: `AgentService.kt`.

### Step 3: Tests
- [ ] Unit test `HistoryCompressionProperties` binding/defaults and the `isHistoryTooBig`
      predicate logic in isolation (plain construction, no Spring context).
- [ ] Mock-executor smoke test (pattern: `AgentEventsTest`) proving the agent still completes a
      normal chooseRecipient→prepareTransfer turn with the new strategy installed — the strategy
      swap doesn't break the existing tool loop.
- **Not attempted:** asserting the semantic content Koog's fact-extraction LLM call produces —
  that needs a real model, same reasoning step 6 used for AC-19b (documented manual/dev
  verification instead of a mock assertion).
- Files: `HistoryCompressionPropertiesTest.kt` (new), `AgentServiceTest.kt` or a new
  `AgentHistoryCompressionTest.kt`.

### Step 4: Docs
- [ ] `feature.md`: AC-21 stays unchecked until the manual long-conversation walkthrough is done;
      note the concept-design decision.
- [ ] `README.md`: Step 8 section (AC-27) — a walkthrough of a long conversation, then a
      follow-up referencing "them" to show the resolved-recipient fact survived compression.

## Acceptance Criteria Mapping
| AC | Verified By |
|----|-------------|
| AC-21: a long conversation compresses to retained facts; the agent still answers correctly using them | Step 3's mock-executor wiring test (no crash/regression) + a manual walkthrough with a real model (README) — semantic correctness of an LLM's own fact extraction isn't meaningfully mockable |

## Risks & Mitigations
- **Extra LLM call adds cost/latency.** Only fires once the threshold is crossed, and only within
  turns that call a tool — most turns pay nothing extra. `app.agent.history-compression.enabled`
  is the off switch.
- **Extracted facts could be wrong or stale** (the LLM mis-summarizes). Mitigated by design: facts
  are advisory conversational context only — every money-moving decision (`prepareTransfer`,
  `undoLastTransfer`, the confirm gate) still resolves against live Postgres state, never against
  anything compressed out of chat history. A bad extraction can produce an awkward reply; it can't
  move money incorrectly.
- **Can't fully unit-test semantic compression.** Accepted, same precedent as AC-19b.

## Estimated Complexity
**Small.** One new properties class, one strategy-construction call at an existing call site, two
`Concept` definitions. No schema change — compression output rides inside the existing
`chat_history` payload `ChatMemory` already owns.
