---
name: new-step
description: Deliver the next tutorial step of koog-spring-boot (spec section in feature.md, plan.md, implementation on a stepN branch, tests, README step section, impl-summary.md). Use when asked to start, plan or implement "step N", a new Koog capability, or the next item in feature.md's Delivery Strategy table.
---

# new-step

Every capability in this repo ships the same way (steps 1–8 in `git log`). Follow it in order and
stop where it says stop: `feature.md` AC-24 requires a pause for human review after each step.

## 1. Spec — `feature.md`

1. Read `AGENTS.md` (rules and the never-without-asking list), then `feature.md` § Delivery
   Strategy to find the step's row, branch name and ingredient.
2. Add or update, using the headings in `docs/specs/TEMPLATE-feature.md`:
   - a `### Step N — stepN-<slug>` block under `## Functional Requirements` with numbered `FR-<nn>`;
   - a `**Step N**` block under `## Acceptance Criteria` with numbered, checkable `AC-<nn>`,
     including the failure case and how the change is switched off;
   - anything newly out of scope under `## Out of Scope`, decisions under `## Resolved Decisions`,
     and a `## Revision History` row.
3. Unclear requirement → write it under `## Open Questions` and ask. Do not guess.

## 2. Plan — `plan.md`

Overwrite `plan.md` using `docs/specs/TEMPLATE-plan.md`: Overview, Architecture Decisions (with
the `docs.koog.ai` page each rests on), checkbox Implementation Steps, AC → test mapping, Risks.
**Stop and show the plan for approval before writing code.**

## 3. Implement — branch `stepN-<slug>` off the previous step

- New versions go only in `gradle/libs.versions.toml`; new dependencies must be added to
  `docs/project.md` § Approved Dependencies, and need the human's OK.
- Schema changes: a new `src/main/resources/db/migration/V<next>__<name>.sql`. Never edit V1–V4.
- Every Koog usage gets teaching comments: KDoc on the concept, an inline `//` at the call site,
  a `docs.koog.ai` link (AC-26). See the numbered comments in `AgentService.runAgent` for the style.
- Tools stage, `AgentService.reply` executes. Do not add a path where the LLM moves money.
- Tests: MockK unit tests and Koog `agents-test` mock executors (`getMockExecutor`,
  `mockLLMAnswer`) for agent behaviour; Testcontainers ITs for anything touching SQL or
  concurrency. Never a live LLM.
- Tick `plan.md` boxes as you go, so a fresh session can resume.

## 4. Verify

```bash
env -u ANTHROPIC_API_KEY -u OPENAI_API_KEY ./gradlew build
```

Docker must be running, or the ITs are skipped and the result proves nothing about them.
Fix detekt/ktlint findings (`./gradlew spotlessApply` for formatting); do not baseline or suppress.

## 5. Document

- `README.md`: a `## Step N — <title>` section with what changed and runnable curl examples
  against the seed data (`README.md` § Seed data). Update the intro's "this branch is step N".
  Examples that hit `/agent/chat` need `APP_AGENT_LIVE_LLM=true` and real keys — write them,
  but do not run them without asking.
- `impl-summary.md`: replace with this step's `## Step N — <title> (stepN-<slug>)` and
  `### Files Created`, `### Files Modified`, `### Acceptance Criteria`, `### Notes`.
- `feature.md`: tick the ACs that tests now prove; leave manual ones unchecked with the reason.
- `docs/project.md`, `docs/agent-flow.md`, `docs/data-model.md`, `AGENTS.md`: fix whatever the
  step made stale. A side note on a design trade goes in `docs/notes/<topic>.md` and gets a row in
  the AGENTS.md "Read before you work on…" table.

## 6. Hand off

Commit with `git commit -s`, then run the `review-step` skill. Open the PR against `main` with
`.github/pull_request_template.md` filled in. **Stop for review** (AC-24); never merge yourself.
