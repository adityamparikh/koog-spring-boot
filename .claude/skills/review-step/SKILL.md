---
name: review-step
description: Review a koog-spring-boot change (a step branch or any diff against main) for money-safety, Koog conventions, spec coverage and test quality before a PR. Use when asked to review a step, review this branch or diff, or check a change before merging.
---

# review-step

Read the change, then go looking for problems. Report findings as `file:line — problem — fix`,
most severe first, and end with a verdict: **ready**, **ready after fixes**, or **not ready**.

## Gather

```bash
git diff main...HEAD --stat
git diff main...HEAD
env -u ANTHROPIC_API_KEY -u OPENAI_API_KEY ./gradlew build   # Docker running, or ITs are skipped
```

Open the step's sections of `feature.md` (FRs and ACs), `plan.md`, and `AGENTS.md`.

## Check, in this order

1. **Money moves only after a "yes".** No tool in `agent/tools/` calls `TransferService.initiate`
   or `cancel`; staging goes through `PendingInteractionStore`; `consume()` still precedes
   `initiate()`/`cancel()` in `AgentService`. Account ids come from `X-User-Id`, never from tool
   arguments the LLM fills in.
2. **Ledger correctness.** `BigDecimal` only; balance changes are conditional SQL updates
   (`WHERE balance >= :amount`, status flips `WHERE status = 'PENDING'`) whose row count is
   checked; nothing reads-modifies-writes a balance; `@Transactional` where several rows change.
3. **Migrations.** Only new `V<n>__*.sql` files; V1–V4 untouched; seed data still matches
   `README.md` § Seed data.
4. **Cost and secrets.** No test can reach a real provider (mock executors only); the
   `app.agent.live-llm=false` default is intact; no key-shaped strings; no `setVerbose(true)`.
5. **Spec coverage.** Each AC of the step maps to a test that asserts the outcome (status code,
   balance, row state), not just `verify { … }` on a mock. Name any AC with no proof.
6. **Conventions.** Versions only in `gradle/libs.versions.toml`; new dependencies listed in
   `docs/project.md`; Koog call sites carry teaching comments and a `docs.koog.ai` link (AC-26);
   endpoints under `/api/v1` with `ProblemDetail` errors.
7. **Docs.** README has the step's section with runnable examples; `impl-summary.md`,
   `feature.md` checkboxes and any stale line in `docs/` or `AGENTS.md` are updated.
8. **Gates.** No new detekt baseline, file-wide `@file:Suppress`, disabled ktlint rule or
   `@Disabled` test without a stated reason.

Do not fix anything during the review unless asked; list it.
