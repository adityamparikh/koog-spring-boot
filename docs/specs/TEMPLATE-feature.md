# Feature: <name>

<!-- The shape of the root feature.md. For a new step of this project, add a `### Step N — stepN-<slug>`
     block under Functional Requirements and a `**Step N**` block under Acceptance Criteria of
     feature.md instead of starting a new file; use this file whole only for a separate feature. -->

## Summary
<What is being built and why, in one paragraph. Name the Koog capability it adds, if any.>

## User Stories
- As a **<role>**, I want <capability> so that <outcome>.

## Functional Requirements

### Step N — `stepN-<slug>`

#### FR-<nn>: <requirement title>
<What the system must do. Name the classes, endpoints, properties and tables it touches.>

## Acceptance Criteria

**Step N**
- [ ] AC-<nn>: <A statement a test can check: an endpoint, a status code, a balance, a row state.
      Say which test proves it, or that it is a manual walkthrough and why.>
- [ ] AC-<nn>: <The failure case: what happens when the step fails — no money moves, which error
      (`ProblemDetail` title and status) the caller sees.>
- [ ] AC-<nn>: <How the change is undone or switched off (a property, a migration that is safe to
      leave, a revert).>

## Technical Scope

### Affected / new modules (under `dev.aparikh.moneytransfer`)
- `<package>` — <what changes>

### Integration points
- <Postgres tables / Flyway migration `V<n>__<name>.sql`, Koog features, compose services, LLM providers>

## Non-Functional Requirements
- **Security:** <secrets, identity via `X-User-Id`, injection surface of tool arguments>
- **Correctness:** <money invariants: `BigDecimal`/`NUMERIC`, atomic conditional updates, no double-send>
- **Cost:** <does this add LLM calls per turn? Tests must use Koog's mock executor.>

## Out of Scope
- <What this deliberately does not do, so an agent knows where to stop.>

## Resolved Decisions
- **OQ-<n> → RESOLVED:** <decision> because <reason>.

## Open Questions
- <Anything still undecided. Write `_None outstanding._` when there is nothing.>

## Revision History

| Date | Change Summary |
|------|----------------|
| <yyyy-mm-dd> | <change> |
