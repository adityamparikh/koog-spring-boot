# Implementation Plan: Overdraft Protection (Step 4 — `step4-overdraft-protection`)

> Scope: **Step 4** of `feature.md` — a `getBalance` tool (FR-11) and **overdraft protection**
> (FR-12): when a requested transfer exceeds the sender's balance, the agent doesn't fail — it
> asks the user how much to send (up to their balance) rather than silently capping. Branch cut
> from `step3-tools`. Covers AC-15, AC-16 (adapted).
>
> **Design: lean / app-side. No custom Koog strategy.** FR-12's literal "custom strategy" is a
> documented deviation — see `docs/notes/custom-strategies.md` for the analysis of what custom
> graphs are actually for and why the overdraft rule (a one-line `if`) isn't one of them.

## Layering (the key decision)
- **Domain (no AI) = the guard.** `TransferService.transfer` already rejects any overdraw
  atomically (`UPDATE … WHERE balance >= :amount` → 0 rows → `InsufficientFundsException`).
  Race-free, holds for *every* caller. We do **not** add a read-then-check `validate()` — that
  would be a weaker (TOCTOU-racy) guard than the atomic UPDATE.
- **AI (agent) = a UX layer.** Look up the balance and, on an over-balance request, *prompt for an
  amount ≤ balance* instead of dead-ending. This never weakens the guard: a stale/wrong balance
  read can't overdraw, because the domain UPDATE still rejects.

So overdraft protection is **UX, not safety**, which is why it's a simple app-side tool behavior.

## Architecture Decisions
- **`getBalance` tool (FR-11):** `@Tool fun getBalance()` on `MoneyTransferTools`, delegating to
  `AccountService.getBalance(accountId)` (`accountId` is the captured per-request field, not an LLM
  arg). AC-15.
- **Overdraft protection in `prepareTransfer` (FR-12):** after resolving the recipient, read the
  balance. If `requested > available`, the tool **does not stage** and **does not silently cap** —
  it returns a message asking the model to prompt the user for an amount up to their balance, so
  the **user's input decides the amount**. When the user supplies an in-balance amount,
  `prepareTransfer` is called again and stages it; the existing step-3 confirm-gate
  (`/reply "yes"` → app-side `TransferService.transfer`) sends it.
- **No custom Koog strategy, no new types/endpoints/`InteractionType`.** The in-balance amount
  reuses `CONFIRMATION`; accepting runs the unchanged confirm-gate.
- **Domain untouched; no new dependencies.**

## Implementation Steps
### Step 1: Balance tool (FR-11)
- [x] Inject `AccountService` into `MoneyTransferTools` (threaded through the per-request
      construction in `AgentService.runAgent`).
- [x] Add `getBalance()` `@Tool`.

### Step 2: Overdraft protection (FR-12)
- [x] `prepareTransfer`: `requested > available` → do not stage; message asks the user for an
      amount up to their balance (zero balance → "nothing to send"). Within balance → stage the
      requested amount as before.

### Step 3: Tests (AC-15, AC-16)
- [x] `MoneyTransferToolsTest#getBalance returns the account balance` (AC-15).
- [x] `MoneyTransferToolsTest#prepareTransfer over-balance asks the user for a smaller amount and
      stages nothing` — requests $50 with a $30 balance, asserts nothing staged and both amounts
      named (AC-16, adapted).
- [x] Existing `prepareTransfer` (within-balance) test stages the requested amount, with a
      `getBalance` stub.
- [ ] (Optional) extend `AgentConfirmationIntegrationTest` to confirm a within-balance amount
      end-to-end against real Postgres.

### Step 4: Documentation (AC-26/29)
- [x] `docs/notes/custom-strategies.md` — why we dropped the custom strategy (what custom graphs
      are for; overdraft isn't one; the verify/revise nuance).
- [ ] README — add the over-balance scenario (request over balance → agent asks for a smaller
      amount → user gives one → confirm → send).

## Acceptance Criteria Mapping
| AC | Verified By |
|----|-------------|
| AC-15: getBalance returns the persisted balance | `MoneyTransferToolsTest#getBalance returns the account balance` |
| AC-16: over-balance → offer to send up to balance; accepting sends it | `MoneyTransferToolsTest#prepareTransfer over-balance asks the user…` (+ confirm-gate IT for execution) |
| AC-25/26/29: no new deps; teaching comments; docs | manual review + `docs/notes/custom-strategies.md` |

## Risks & Mitigations
- **Overdraft protection is UX, not safety.** → intentional; the domain atomic UPDATE is the real
  guard, so a wrong/stale balance read can't overdraw.
- **Deviating from FR-12's "custom strategy".** → deliberate and documented
  (`docs/notes/custom-strategies.md`); the rule is a one-line `if`, and no custom-graph situation
  (routing, autonomous verify/revise, parallel, …) fits a single-capability transfer agent.
- **Balance read may be stale by confirm time.** → on confirm, the domain rejects if the amount now
  exceeds balance; the confirm-gate surfaces "would exceed your balance" and the user retries.

## Estimated Complexity
**Low.** ~40 net lines in existing files, no new types/endpoints/dependencies. Overdraft
protection is a thin agent-layer prompt over an already-safe domain.
