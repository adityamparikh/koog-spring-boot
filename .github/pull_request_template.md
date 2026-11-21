## What and why

<!-- Which step (feature.md FR/AC numbers) or issue this implements, in two or three lines. -->

## Checklist (the author ticks; the reviewer checks)

Reviewer: the repo owner. Every PR into `main` is read by a human before merge; an agent may
open a PR but never merges one.

- [ ] `./gradlew build` is green **with Docker running** (otherwise the Testcontainers ITs were skipped), and CI is green
- [ ] No money moves without the confirm gate: tools only stage; `consume()` before `initiate()`/`cancel()`
- [ ] No new or edited test reaches a real LLM (Koog mock executor only); `app.agent.live-llm` still defaults to `false`
- [ ] Schema changes are a new `V<n>__*.sql`; `V1`–`V4` untouched
- [ ] New versions only in `gradle/libs.versions.toml`; new dependencies listed in `docs/project.md`
- [ ] Koog call sites carry teaching comments and a `docs.koog.ai` link (feature.md AC-26)
- [ ] `README.md` has the step's `## Step N` section with runnable examples (AC-27)
- [ ] `feature.md` ACs ticked where a test proves them; `plan.md` / `impl-summary.md` updated
- [ ] No detekt baseline, file-wide suppression, disabled ktlint rule or `@Disabled` test added without a reason here
- [ ] `review-step` skill run (`.claude/skills/review-step/SKILL.md`); findings fixed or answered below

## How it was checked

<!-- Commands run, tests added, and any manual walkthrough (say if it used live LLM keys). -->
