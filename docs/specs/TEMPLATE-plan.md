# Implementation Plan: <name> (Step N — `stepN-<slug>`)

> Scope: **Step N** of `feature.md` (FR-<nn>; AC-<nn>). Branch `stepN-<slug>` off `main`
> (after step N-1 merged).

<!-- The shape of the root plan.md. A new step overwrites plan.md; the previous plan stays in git history. -->

## Overview
<Today's behaviour, what is wrong or missing, and what this step changes.>

## Architecture Decisions
- **<Decision>** over <alternative>: <why, with the Koog / Spring doc link it rests on>.

## Implementation Steps

### Step 1: Config
- [ ] <property in `application.properties` + `@ConfigurationProperties` class in `agent/config/` or the feature package>

### Step 2: <core change>
- [ ] <file — change; teaching comments at every new Koog call site (AC-26)>

### Step 3: Tests
- [ ] <unit test (MockK / Koog `agents-test` mock executor) — what it asserts>
- [ ] <Testcontainers IT (`@Testcontainers(disabledWithoutDocker = true)`) — what it asserts>

### Step 4: Docs
- [ ] `README.md` — new `## Step N — <title>` section with runnable curl examples
- [ ] `feature.md` — tick the ACs this step meets; leave manual ones unchecked with a reason
- [ ] `impl-summary.md` — files created/modified, AC status
- [ ] `docs/project.md` / `docs/*.md` / `AGENTS.md` — anything this step makes stale

## Acceptance Criteria Mapping

| AC | Proven by |
|----|-----------|
| AC-<nn> | `<TestClass>.<test name>` / manual walkthrough (why) |

## Risks & Mitigations
- **<What can go wrong, including when an LLM call fails or returns garbage>** — <mitigation>.
- **Undo:** <how to switch it off or revert>.

## Estimated Complexity
<Small / Medium / Large, and why.>
