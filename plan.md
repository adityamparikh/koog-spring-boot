# Implementation Plan: Async Settlement + Undo (Step 7 — `step7-rollback`)

> Scope: **Step 7** of `feature.md` (FR-16, FR-20, FR-21, FR-22; AC-20, AC-30–33). Split the
> single debit+credit transaction into *debit-and-record at confirm* + *credit at settlement*
> (lifecycle L5/L6), add the undo window while `PENDING`, surface it to the agent, and add the
> advisory balance hint. Branch `step7-rollback` off `step6-observability`.

## Overview
`TransferService.transfer` (one transaction: debit + credit + `COMPLETED` row) becomes three
operations on a transfer state machine:

- **`initiate`** — atomic debit (existing conditional UPDATE) + insert `PENDING` row with
  `settle_at = now + delay`, one transaction. Called from the REST create endpoint and from the
  agent confirm gate. Funds are reserved; available balance stays honest.
- **`settle`** — a `@Scheduled` settler polls `PENDING` rows past `settle_at`; per row, in one
  transaction: conditional flip `PENDING → SETTLED` + credit recipient. Recipient gone →
  `PENDING → FAILED` + re-credit sender. The Postgres table *is* the work queue (outbox style).
- **`cancel`** — conditional flip `PENDING → CANCELLED` + re-credit sender. `SETTLED` is
  terminal — never reversed. The cancel-vs-settle race is decided by
  `WHERE status = 'PENDING'`: one writer wins, the loser no-ops.

The domain split applies to **all** transfers (REST and agent) — the REST create response now
returns a `PENDING` transfer. The HITL boundary is untouched; only what happens after "yes"
changes. **No custom strategy graph** — `singleRunStrategy` stays (see the spec's strategy
note and `docs/notes/custom-strategies.md`).

## Architecture Decisions
- **States replace placeholders:** `TransferStatus` becomes `PENDING, SETTLED, CANCELLED,
  FAILED` (all but `PENDING` terminal). The step-1 placeholders `COMPLETED/REVERSED/REVERSAL`
  are removed; Flyway V4 rewrites existing `COMPLETED` rows to `SETTLED` (historically true —
  they did settle).
- **Settler transactionality:** each row settles in its own transaction (flip-then-credit),
  so one poisoned row can't roll back a batch; the conditional flip makes re-delivery after a
  crash harmless (an already-`SETTLED` row is skipped).
- **Undo confirmation is a new pending variant:** `PendingInteraction.CancelConfirmation`
  (Jackson `kind = "CANCEL_CONFIRMATION"`) joins the sealed hierarchy — the undo goes through
  the *same* deterministic confirm gate as sends; `reply()` executes `cancel` app-side. New
  JSON subtype is additive, so persisted step-5/6 payloads still deserialize.
- **Config:** `TransferProperties` (`app.transfer.settlement-delay: Duration = PT2M`,
  `app.transfer.settlement-poll-ms = 5000`); integration tests override to ~`PT1S`/fast poll.
  `@EnableScheduling` already on (`MoneyTransferApplication`).
- **Balance hint is advisory only** (FR-22): live balance appended to the per-turn system
  prompt; `StagedTransfer` gains a nullable `balanceAfter` (JSON-backward-compatible) shown in
  the confirmation summary. All existing guards (re-specify flow, tool refusal, atomic debit)
  unchanged.
- **Teaching comments** (AC-26) on every Koog call site touched; ordinary domain code keeps
  normal KDoc.

## Implementation Steps

### Step 1: Schema (Flyway V4)
- [x] `V4__async_settlement.sql`: `ALTER TABLE transfer ADD COLUMN settle_at TIMESTAMPTZ`;
      `UPDATE transfer SET status = 'SETTLED' WHERE status = 'COMPLETED'`; index
      `(status, settle_at)` for the settler poll.
- Files: `src/main/resources/db/migration/V4__async_settlement.sql`.

### Step 2: Domain layer
- [x] `TransferStatus` → `PENDING, SETTLED, CANCELLED, FAILED`; `Transfer` gains
      `settleAt: Instant?`.
- [x] `TransferRepository`: `findByStatusAndSettleAtLessThanEqual(PENDING, now)` (settler poll);
      `@Modifying` conditional flips `markSettled/markCancelled/markFailed(id)` each guarded by
      `WHERE status = 'PENDING'` returning the update count; list query becomes
      sender-or-recipient so recipients see incoming settled transfers.
- [x] New exceptions in `common/DomainExceptions.kt`: `TransferNotCancellableException`
      (settled/already-cancelled → 409), `UnknownTransferException` (404) + handler mappings.
- Files: `Transfer.kt`, `TransferRepository.kt`, `common/DomainExceptions.kt`,
  `common/GlobalExceptionHandler.kt`.

### Step 3: Service layer (the L5/L6 split)
- [x] `TransferService.transfer(...)` → **`initiate(...)`**: validations + existence checks as
      today; atomic debit; save `PENDING` row with `settleAt`. (Keep the method's exception
      contract; callers rename.)
- [x] `TransferService.cancel(transferId, actingAccountId)`: ownership check (sender only);
      `markCancelled` → 1 row: credit sender back, return the row; 0 rows: throw
      `TransferNotCancellableException`/`UnknownTransferException` by current status.
- [x] `TransferSettler` (`@Component`, transfer package): `@Scheduled(fixedDelayString =
      "${app.transfer.settlement-poll-ms:5000}")` — poll due rows; per row `settleOne(id)`
      (`@Transactional`): `markSettled` → credit recipient; recipient missing → `markFailed` +
      re-credit sender.
- [x] `TransferProperties` (`@ConfigurationProperties("app.transfer")`).
- Files: `TransferService.kt`, `TransferSettler.kt` (new), `TransferProperties.kt` (new),
  `application.properties`.

### Step 4: REST layer
- [x] `POST /api/v1/transfers/{id}/cancel` on `TransferController` → `cancel(...)`, returns the
      `CANCELLED` transfer; create endpoint unchanged in shape (response now carries
      `PENDING` + `settleAt`). `TransferResponse` gains `settleAt`.
- Files: `TransferController.kt`.

### Step 5: Agent layer (FR-21, FR-22)
- [x] `PendingInteraction.CancelConfirmation(transferId, summary)` + `@JsonSubTypes` entry.
- [x] `MoneyTransferTools`: new tools `getRecentTransfers()` (id | recipient | amount | status |
      settles-at, via `TransferService`) and `undoLastTransfer()` (most recent `PENDING`
      transfer for the sender → stage `CancelConfirmation`; none pending → tell the model to
      explain nothing is cancellable, mentioning any recently settled transfer is final).
      `prepareTransfer` computes `balanceAfter` into `StagedTransfer` (summary shows it).
- [x] `AgentService`: inject `TransferService` usage updates (`initiate`); `resolveConfirmation`
      branches on the confirmation variant — staged send → `initiate` + reply
      "Queued — $X to <name> settles in about <delay>; say 'undo' before then to cancel";
      cancel confirmation → `TransferService.cancel` + reply (settled race → honest "it already
      settled" message). System prompt gains the live balance line (FR-22).
- Files: `PendingInteractionStore.kt`, `MoneyTransferTools.kt`, `AgentService.kt`.

### Step 6: Tests
- [x] **Update existing:** `TransferServiceTest` (transfer→initiate: asserts `PENDING`, debit
      only), `MoneyTransferIntegrationTest` + `TransferConcurrencyIT` (settle explicitly or
      short-delay await for balance assertions), `AgentServiceTest` /
      `AgentConfirmationIntegrationTest` ("Done — sent" → "Queued …"), `MoneyTransferToolsTest`
      (balance-after in summary).
- [x] **New unit:** `TransferSettlerTest` — settles due `PENDING`, skips future `settle_at`,
      recipient-gone → `FAILED` + sender re-credited, already-terminal row no-ops.
- [x] **New unit:** cancel cases in `TransferServiceTest` — cancel `PENDING` (re-credit),
      cancel `SETTLED`/`CANCELLED` → `TransferNotCancellableException`, non-owner rejected.
- [x] **New IT (Testcontainers):** `SettlementLifecycleIT` — initiate → `PENDING` + debited →
      await settle (~1 s delay) → `SETTLED` + credited (AC-30); undo before settle → `CANCELLED`
      + refunded, then undo again → 409 (AC-20); concurrent cancel-vs-settle → exactly one wins,
      balances consistent (AC-31).
- [x] **New agent tests (mock executor):** undo tool stages `CancelConfirmation`, executes only
      after "yes" (AC-32); system prompt contains balance; queued wording asserted (AC-33 & FR-21).
- Files: existing test classes above; `TransferSettlerTest.kt`, `SettlementLifecycleIT.kt` (new).

### Step 7: Docs (AC-26/29)
- [x] README "Step 7 — Async settlement & undo" section: curl walkthrough — send via agent →
      "Queued", `getRecentTransfers`/status shows `PENDING`, undo → `CANCELLED` + refund; then a
      second send left to settle → `SETTLED`, undo attempt → rejected.
- [x] Update `docs/agent-flow.md` / `docs/data-model.md` state diagram if present; teaching
      comments at all touched Koog call sites.

## Acceptance Criteria Mapping
| AC | Verified By |
|----|-------------|
| AC-20: cancel PENDING refunds; SETTLED/CANCELLED undo rejected | `TransferServiceTest` cancel cases; `SettlementLifecycleIT#undoBeforeSettle` |
| AC-30: confirm debits + PENDING; settler credits + SETTLED after delay | `SettlementLifecycleIT#settlesAfterDelay`; `TransferSettlerTest` |
| AC-31: cancel-vs-settle race — one winner, balances consistent | `SettlementLifecycleIT#cancelVsSettleRace` |
| AC-32: agent undo only after CONFIRMATION "yes"; queued wording; status via tool | `AgentServiceTest` (mock executor) undo + wording cases |
| AC-33: balance-after in summary; re-specify flow unchanged | `MoneyTransferToolsTest`; existing overdraft tests stay green |

## Risks & Mitigations
- **Existing tests assume instant settlement** (step-1 ITs assert recipient balance right after
  create). → Update them deliberately (await settlement or call `settleOne` directly); treat
  every such edit as a *contract change acknowledged*, not a test "fix".
- **Settler running during unrelated ITs** could settle rows mid-assertion. → Default poll is
  5 s with `PT2M` delay in main config; tests that need settlement opt into short overrides,
  others see stable `PENDING` rows.
- **Enum rename breaks persisted rows** (`COMPLETED` in seed/history). → V4 migrates data in the
  same migration that assumes new values; no code reads old names afterward.
- **New Jackson subtype in `pending_interaction` payloads.** → Additive `@JsonSubTypes` entry;
  old `CONFIRMATION`/`CLARIFICATION` payloads deserialize unchanged (verify in
  `ConversationCleanupTest`/store test).
- **Double refund on racing cancel + settler-failure path.** → Every balance mutation is behind
  a conditional status flip; only the single winning flip performs its credit.

## Estimated Complexity
**Medium-High.** The state machine and conditional flips are small, but this is the first step
that changes an existing domain contract (instant → async settlement), so the blast radius is
in the callers and tests, not the new code. The race tests (AC-31) need care to be
deterministic.
