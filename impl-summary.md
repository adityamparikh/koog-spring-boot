# Implementation Complete

## Step 7 — Async Settlement + Undo (`step7-rollback`)

### Files Created
- `src/main/resources/db/migration/V4__async_settlement.sql` — `settle_at` column, `COMPLETED`→`SETTLED` data migration, partial index for the settler poll
- `src/main/kotlin/dev/aparikh/moneytransfer/transfer/TransferProperties.kt` — `app.transfer.settlement-delay` (PT2M) + `settlement-poll-ms`
- `src/main/kotlin/dev/aparikh/moneytransfer/transfer/TransferSettler.kt` — `@Scheduled` outbox-style poller; per-row delegation with failure isolation
- `src/test/kotlin/dev/aparikh/moneytransfer/transfer/TransferSettlerTest.kt` — poll-loop unit tests
- `src/test/kotlin/dev/aparikh/moneytransfer/transfer/SettlementLifecycleIT.kt` — scheduler-driven settle (AC-30) + cancel-vs-settle race (AC-31), real Postgres

### Files Modified
- `Transfer.kt` — `TransferStatus` → `PENDING | SETTLED | CANCELLED | FAILED`; `settleAt` field
- `TransferRepository.kt` — settler poll query, atomic conditional flips (`markSettled/markCancelled/markFailed`), sender-or-recipient list
- `TransferService.kt` — `transfer()` split into `initiate` (debit + PENDING) / `settle` (credit behind winning flip; FAILED+refund path) / `cancel` (undo while PENDING only); `transfersFor`, `latestPendingFor`
- `TransferController.kt` — `POST /{id}/cancel`; response carries `settleAt`; list shows sent+received
- `common/DomainExceptions.kt` + `GlobalExceptionHandler.kt` — `UnknownTransferException` (404), `TransferNotCancellableException` (409)
- `agent/PendingInteractionStore.kt` — `PendingInteraction.CancelConfirmation` variant; `StagedTransfer.balanceAfter` (summary shows "balance after: $X")
- `agent/MoneyTransferTools.kt` — new tools `getRecentTransfers`, `undoLastTransfer` (stages only); `prepareTransfer` computes balance-after
- `agent/AgentService.kt` — routes cancel confirmations through the same deterministic gate; post-confirm reply is "Queued … say 'undo'"; live balance in system prompt
- `application.properties` — `app.transfer.*` settlement config
- Tests updated for the contract change (instant → async settlement): `TransferServiceTest`, `TransferConcurrencyIT`, `MoneyTransferIntegrationTest`, `AgentServiceTest`, `AgentConfirmationIntegrationTest`, `MoneyTransferToolsTest`
- Docs: README step-7 section (send→status→undo walkthrough, REST lifecycle), `docs/data-model.md` (state machine), `docs/agent-flow.md` (queued wording)

### Acceptance Criteria
- [x] AC-20: Passed — `TransferServiceTest` cancel cases; `MoneyTransferIntegrationTest#cancelling a pending transfer…` + `#a settled transfer cannot be cancelled`; `AgentConfirmationIntegrationTest#affirming a staged cancellation…`
- [x] AC-30: Passed — `SettlementLifecycleIT#a confirmed transfer settles on its own after the settlement delay`; `MoneyTransferIntegrationTest#happy path…`
- [x] AC-31: Passed — `SettlementLifecycleIT#racing cancel and settle produce exactly one winner and consistent balances`
- [x] AC-32: Passed — `AgentServiceTest` cancel-confirmation suite (affirm-once, settled-race message, deny, unclear); `MoneyTransferToolsTest#undoLastTransfer stages…`
- [x] AC-33: Passed — `MoneyTransferToolsTest#prepareTransfer stages…` (balance-after in summary); over-balance re-specify tests unchanged and green

Full build: `./gradlew build` — 67 tests, 0 failures (unit + Testcontainers ITs).

### Notes
- `settle()` lives on `TransferService` (not the settler) — keeps all three state transitions together and avoids Spring's self-invocation `@Transactional` trap the plan's `settleOne` sketch would have hit. `settle()` does not check `settle_at` (dueness is the poller's contract), which also lets tests drive the lifecycle deterministically.
- `MoneyTransferTools` now references `TransferService` for reads (`transfersFor`, `latestPendingFor`); the step-3 claim "tools structurally cannot move money" is now "tools call only read methods — every money movement still executes app-side behind the confirm gate" (test comment updated accordingly).
- V4 migrates historical `COMPLETED` rows to `SETTLED` (truthful: they did settle); `settleAt` is null only on pre-step-7 rows.
