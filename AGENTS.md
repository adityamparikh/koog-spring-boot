# AGENTS.md — koog-spring-boot

Kotlin + Spring Boot 3.5 money-transfer service whose `/api/v1/agent/*` endpoints are a Koog AI
agent. Built as a tutorial, one `stepN-*` branch per capability (`feature.md` § Delivery Strategy).
Ledger money is simulated (no payment rails), but **LLM calls are real and paid**.

## Never without asking

Stop and ask the human first. Each of these spends money, destroys data, or is shared.

- **Live LLM calls.** Setting `APP_AGENT_LIVE_LLM=true` / `app.agent.live-llm=true`, or calling
  `/api/v1/agent/chat` or `/reply` on an app started with real `ANTHROPIC_API_KEY` /
  `OPENAI_API_KEY`. Every chat turn is a paid API call. Tests never need it: they use Koog's
  `agents-test` mock executor. Do not change the `false` default in `application.properties`.
- **Editing an applied migration.** `src/main/resources/db/migration/V1__init.sql` to
  `V4__async_settlement.sql` have run on real databases; Flyway checksums make an edit fail
  startup. Schema changes go in a new `V5__<name>.sql`.
- **`docker compose down -v`** (or deleting the compose volumes). It wipes the local Postgres:
  ledger, contacts, chat history, checkpoints, pending confirmations.
- **Pushing, force-pushing, or merging to `main`**, and any GitHub settings change.
- **Weakening a gate.** Adding a detekt baseline, suppressing a rule file-wide, disabling a
  ktlint rule, or skipping tests to get green. Fix the finding, or ask.

## Commands

| What | Command |
|------|---------|
| Everything (compile, detekt, ktlint, all tests) | `./gradlew build` |
| Tests only / one class | `./gradlew test` / `./gradlew test --tests 'dev.aparikh.moneytransfer.transfer.TransferServiceTest'` |
| Lint | `./gradlew detekt` (rules: `config/detekt/detekt.yml`) |
| Format check / fix | `./gradlew spotlessCheck` / `./gradlew spotlessApply` |
| Run the app (starts Postgres + LGTM from `compose.yaml`) | `./gradlew bootRun`, Swagger at http://localhost:8080/swagger-ui.html |
| Infra only | `docker compose up -d` |

`./gradlew build` must pass before you say a change is done. The `*IT`, `*IntegrationTest`,
`AgentAutoConfigurationTest` and `LiveLlmOptInTest` classes use Testcontainers with
`disabledWithoutDocker = true`: **without Docker they are skipped, not passed**. If Docker is
not running, say so instead of reporting green. Prerequisites: JDK 25 and Docker.
Test failures print the assertion and `file:line` on the console; the HTML report is
`build/reports/tests/test/index.html`, detekt's is `build/reports/detekt/detekt.html`.

## Where things live

- `src/main/kotlin/dev/aparikh/moneytransfer/` — feature-first packages:
  `account/`, `contact/`, `transfer/` (incl. `TransferSettler`, the `@Scheduled` settler),
  `agent/` (`AgentService` builds one Koog `AIAgent` per turn; `AgentController`),
  `agent/tools/` (`MoneyTransferTools`, the Koog `ToolSet`), `agent/hitl/` (pending
  clarification/confirmation store, yes/no parsing), `agent/config/` (`@ConfigurationProperties`,
  OTel and persistence beans), `common/` (domain exceptions, `ProblemDetail` handler, OpenAPI).
- `src/main/resources/application.properties` — all `app.*` and `ai.koog.*` settings.
  `db/migration/` — Flyway `V1`–`V4` (schema, seed data, Koog tables, async settlement).
- `src/test/kotlin/...` mirrors main. `src/test/resources/application-test.properties` is the
  test profile (see rules).
- `gradle/libs.versions.toml` — every version and coordinate. `config/detekt/detekt.yml`,
  `.editorconfig` — lint and format rules.
- `compose.yaml` — `postgres:17` and `grafana/otel-lgtm:0.11.4`.
- Root `feature.md` (whole-project spec: FRs, ACs per step, out of scope, decisions),
  `plan.md` (current step's plan), `impl-summary.md` (what the last step changed).
- `.claude/skills/` — the repeatable workflows (below). `docs/specs/` — spec templates.

## Read before you work on…

| Topic | File |
|-------|------|
| Stack, conventions, approved dependencies (ask before adding one not listed) | `docs/project.md` |
| Accounts / contacts / transfers, ER diagram, table mapping | `docs/data-model.md` |
| One `/chat` turn, the HITL sequence, settle vs undo | `docs/agent-flow.md` |
| ChatMemory vs Persistence vs the app-side confirm gate; two dependency traps | `docs/notes/persistence.md` |
| OTel export, the per-request SDK limitation, **curl queries for traces/metrics** | `docs/notes/observability.md` |
| Why overdraft protection is not a custom Koog strategy | `docs/notes/custom-strategies.md` |
| `runBlocking` vs `runTest` in tests, why `chat` is `suspend` | `docs/notes/coroutine-testing.md` |
| Curl walkthroughs per step, seed data | `README.md` |
| Requirements, acceptance criteria, non-goals | `feature.md` |

## Rules the code will not tell you

- **No money moves without a "yes".** Tools only *stage* (`PendingInteractionStore`);
  `AgentService.reply` executes after `AffirmationInterpreter` sees an affirmation. Never let a
  tool call `TransferService.initiate`/`cancel` directly. In `resolveConfirmation`, `consume()`
  must stay before `initiate()` (a crash then loses the intent rather than double-sending).
- **Identity comes from the `X-User-Id` header** and is captured in the per-request
  `MoneyTransferTools`; never accept an account id as an LLM-supplied tool argument.
- **Never `setVerbose(true)`** on Koog's OpenTelemetry feature: it exports prompts, amounts and
  names into spans.
- **Versions only in `gradle/libs.versions.toml`.** The one exception is
  `extra["kotlin-serialization.version"]` in `build.gradle.kts` (Koog's JDBC providers need
  kotlinx-serialization 1.9.0; it compiles without it and fails at runtime). Keep it.
- **The test config must stay `application-test.properties`.** A test-side
  `application.properties` silently shadows the main one.
- Keep the `otel-lgtm` healthcheck in `compose.yaml`; without it the first request's spans are
  dropped. Keep the detekt Kotlin-version block in `build.gradle.kts`; detekt refuses to run
  without it.
- Koog code carries teaching comments (KDoc, inline `//` at each Koog call site, a
  `docs.koog.ai` link) — `feature.md` AC-26. New Koog usage needs the same.
- REST lives under `/api/v1`; errors are `ProblemDetail` via `common/GlobalExceptionHandler`.
- Secrets: real keys only in your shell or a gitignored `.env` (`.env.example` shows the shape).

## Workflows

- **New step / feature:** `.claude/skills/new-step/SKILL.md` (spec → plan → implement → README
  section → `impl-summary.md`), using `docs/specs/TEMPLATE-feature.md` and
  `docs/specs/TEMPLATE-plan.md`.
- **Review before a PR:** `.claude/skills/review-step/SKILL.md`; the PR checklist is
  `.github/pull_request_template.md`.
- **Commits:** `git commit -s` (DCO sign-off). Branch per step; never commit to `main`.
- **CI:** `.github/workflows/ci.yml` runs `./gradlew build` on every pull request and push to
  `main`, with no LLM keys.
- Left in flight: the unchecked boxes in `plan.md` and the `## Open Questions` of `feature.md`.
