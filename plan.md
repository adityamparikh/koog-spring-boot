# Implementation Plan: Koog Spring Boot Starter Integration (Step 2 — `step2-koog-spring-boot`)

> Scope: **Step 2 only** of `feature.md` — wire the Koog Spring Boot starter into the
> already-merged step-1 domain, add a single-turn `/api/v1/agent/chat` endpoint with a
> hand-rolled Anthropic→OpenAI fallback, and set up the `ai.koog:agents-test` mock-executor
> pattern so **no test hits a real LLM API**. No tools, no HITL, no checkpointing yet
> (those arrive at steps 3/5). Branch `step2-koog-spring-boot` cut from `main`
> (step 1 merged via PR #2).
>
> Covers FR-06, FR-07 and AC-09, AC-10, AC-28 (partially — full fallback proof), plus the
> cross-cutting AC-25 (catalog versions), AC-26 (Koog teaching comments, starting now),
> AC-27/AC-29 (incremental README section).

## Pre-implementation research (already done)
Verified directly against the JetBrains `koog` repo source at tag `1.0.0` / Maven Central
(not just prose docs), since `feature.md`'s model names ("Sonnet 5", "Opus 4.8") don't
exist verbatim as Koog enum entries:
- `ai.koog:koog-spring-boot-starter:1.0.0-beta` — the Spring starter module is still
  `-beta` even though core Koog is `1.0.0`.
- `ai.koog:agents-test:1.0.0` — stable, not beta.
- Auto-configured beans (confirmed from `@AutoConfiguration` source): `anthropicLLMClient`,
  `anthropicExecutor: PromptExecutor`, `openAILLMClient`, `openAIExecutor: PromptExecutor`,
  and `multiLLMPromptExecutor: MultiLLMPromptExecutor` (aggregates every enabled provider's
  client). Gated on `ai.koog.<provider>.enabled` (defaults `true`) + non-blank
  `ai.koog.<provider>.api-key` (defaults to `${ANTHROPIC_API_KEY:}` / `${OPENAI_API_KEY:}`).
- `AnthropicModels` enum entries that actually exist: `Haiku_4_5`, `Sonnet_4`, `Sonnet_4_5`,
  `Sonnet_4_6`, `Opus_4`, `Opus_4_1`, `Opus_4_5`, `Opus_4_6`, `Opus_4_7`. There is no
  `Sonnet_5`/`Opus_4_8`. Closest/newest: `Sonnet_4_6`, `Opus_4_7`.
- `OpenAIModels.Chat` **does** have an exact match for the spec's fallback model:
  `GPT5_4`.
- **Koog's resilience is per-provider, not cross-provider.** Each auto-configured executor
  already wraps its raw client in `RetryingLLMClient` (`AnthropicLLMAutoConfiguration`:
  `MultiLLMPromptExecutor(LLMProvider.Anthropic to client.toRetryingClient(properties.retry))`),
  which retries the **same** provider on transient faults (HTTP `429/500/502/503/504/529`,
  keywords like `rate limit`/`overloaded`/`timeout`; default 3 attempts, exponential backoff
  1s→30s + jitter, configurable via `ai.koog.anthropic.retry.*`/`ai.koog.openai.retry.*`).
  Separately, **`MultiLLMPromptExecutor`'s `fallback` only fires when no client is registered
  for a provider at all** (`FallbackPromptExecutorSettings`) — confirmed from
  `MultiLLMPromptExecutor.execute()`'s `when` branching purely on `provider in llmClients`,
  with no try/catch around the client call. So once `RetryingLLMClient` exhausts its retries
  on a *sustained* Anthropic failure, the exception propagates uncaught — Koog itself never
  tries OpenAI. The "Anthropic errors → retry on OpenAI" behavior in FR-07/AC-28 is therefore
  a real gap in the framework, not a misunderstanding of it, and must be **hand-rolled** in
  `AgentService`.
- Koog's own documented Spring pattern (`docs.koog.ai/spring-boot`) injects `anthropicExecutor`
  directly and calls `executor.execute(prompt, model)` from a `suspend fun` controller method
  — there is no officially documented "AIAgent wired into a Spring controller" sample. Since
  this project is explicitly a Koog-concepts teaching build (AC-26), we still construct a real
  `AIAgent(promptExecutor, llmModel)` per FR-07's wording, but conversation continuity across
  HTTP calls (no checkpointing until step 5) is **our own design**, documented below.

## Architecture Decisions
- **Model mapping (documented substitution):** `app.agent.anthropic-model` defaults to
  `Sonnet_4_6` (stands in for spec's "Sonnet 5" — nearest available Koog enum) and
  `app.agent.anthropic-complex-model` defaults to `Opus_4_7` (stands in for "Opus 4.8").
  `app.agent.openai-fallback-model` defaults to `GPT5_4` — an **exact** match, no
  substitution needed. All three are `@ConfigurationProperties("app.agent")`-bound strings
  resolved to the enum via `valueOf`, so a future Koog bump that adds true `Sonnet_5`/
  `Opus_4_8` entries is a one-line properties change, not a code change.
- **"Complex turn" model selection deferred:** step 2 has no tool/strategy graph, so there is
  no signal yet for what counts as a "complex" turn. `anthropic-complex-model` is wired
  through `AgentModelProperties` but unused by `AgentService.chat` until step 4's custom
  strategy (FR-11/12) gives it a real trigger. Noted as a risk below so it isn't lost.
- **Hand-rolled fallback, not `MultiLLMPromptExecutor.fallback`:** `AgentService.chat` tries
  `AIAgent(anthropicExecutor, anthropicModel).run(...)`; any exception falls back to
  `AIAgent(openAIExecutor, openAiFallbackModel).run(...)` with the same input. This is the
  behavior AC-28 requires and is exercised with an `agents-test` mock that fails the
  Anthropic client.
- **Conversation continuity without checkpointing:** each `/agent/chat` call constructs a
  fresh `AIAgent` (Koog's basic constructor is a single-shot `run(input: String)`, confirmed
  from the JetBrains source; there's no documented cross-request session API at this version).
  A small in-memory `ConversationStore` (`ConcurrentHashMap<UUID, List<Turn>>`) keeps prior
  turns per `conversationId`; each call renders the stored transcript plus the new message
  into one input string passed to `agent.run(...)`. This satisfies FR-07's "conversationId for
  follow-up turns" now, and is the seam step 3 replaces with real Koog checkpoint/pause-resume
  and step 5 moves to Postgres — **the `ConversationStore` interface, not its callers, changes**.
- **New `agent` package** under `dev.aparikh.moneytransfer.agent`, matching the existing
  feature-first module layout (`account`, `contact`, `transfer`, `common`).
- **Koog teaching-comment convention starts now (AC-26):** every Koog call site
  (`AIAgent(...)`, `PromptExecutor.execute`/`.run`, the auto-configured executor beans) gets
  KDoc explaining the concept, an inline `//` comment for that specific call, and a
  `docs.koog.ai` link. Plain Spring/domain code (DTOs, controller plumbing) keeps normal KDoc
  only.
- **Test isolation:** `ai.koog:agents-test`'s `getMockExecutor` replaces the real
  `anthropicExecutor`/`openAIExecutor` beans in the test `ApplicationContext` (via
  `@TestConfiguration` + `@Primary`), so `AgentControllerTest`/`AgentServiceTest` never call a
  real LLM API — matching FR-06's testability requirement from this step onward.
- **README grows incrementally (AC-27/29):** step 2's PR adds a "Step 2: AI Agent Chat"
  section to `README.md` with run instructions (env vars `ANTHROPIC_API_KEY`,
  `OPENAI_API_KEY`) and an example `curl` request/response for `/api/v1/agent/chat`.

## Implementation Steps

### Step 0: Version catalog & build dependencies (FR-06)
- [ ] `gradle/libs.versions.toml`:
  - Bump `koog = "1.0.0"` entries to reflect the Spring starter's actual version — add a
    separate `koogSpringBootStarter = "1.0.0-beta"` version (core `koog-agents`/`agents-tools`
    stay `1.0.0`; the Spring starter artifact is independently versioned `-beta`).
  - Add library aliases: `koog-spring-boot-starter = { module = "ai.koog:koog-spring-boot-starter", version.ref = "koogSpringBootStarter" }`
    and `koog-agents-test = { module = "ai.koog:agents-test", version.ref = "koog" }`.
  - Add `kotlin-serialization` version.ref use (Koog requires kotlinx-serialization
    1.10.0+); confirm the Boot 3.5.x BOM's transitive kotlinx-serialization satisfies this,
    else pin explicitly.
- [ ] `build.gradle.kts`:
  - `implementation(libs.koog.spring.boot.starter)` (replaces the unused placeholder
    `koog-agents`/`koog-tools`/`koog-executor-openai-client` aliases carried since step 1 —
    the starter transitively brings the agent/tool/executor jars).
  - `testImplementation(libs.koog.agents.test)`.
  - Apply `alias(libs.plugins.kotlin.serialization)` (Koog's agent/tool models use
    kotlinx-serialization).
- Files: `gradle/libs.versions.toml`, `build.gradle.kts`.

### Step 1: Configuration (FR-06)
- [ ] `src/main/resources/application.properties`:
  - `ai.koog.anthropic.api-key=${ANTHROPIC_API_KEY:}` and
    `ai.koog.openai.api-key=${OPENAI_API_KEY:}` (both `enabled` default `true`; only the
    key needs to be set — document that a blank key disables that provider's
    auto-configuration).
  - `app.agent.anthropic-model=Sonnet_4_6`, `app.agent.anthropic-complex-model=Opus_4_7`,
    `app.agent.openai-fallback-model=GPT5_4` (our own properties, see Architecture Decisions).
- [ ] Confirm the app **fails to start clearly** (not silently) if both API keys are blank —
  covered by the `AgentModelProperties`/`AgentService` construction-time check in Step 2
  below, not by Koog itself.
- Files: `application.properties`.

### Step 2: Agent domain/service layer (FR-07)
- [ ] `agent/AgentModelProperties.kt` — `@ConfigurationProperties("app.agent")` data class:
      `anthropicModel: String`, `anthropicComplexModel: String`, `openAiFallbackModel: String`;
      resolves each to `AnthropicModels`/`OpenAIModels.Chat` via `valueOf` at bean-creation
      time (fail fast on a typo'd property, not on first request).
- [ ] `agent/ConversationStore.kt` — in-memory store: `data class Turn(role, content)`,
      `class ConversationStore { fun historyOf(id: UUID): List<Turn>; fun append(id: UUID, userTurn: Turn, assistantTurn: Turn) }`
      backed by `ConcurrentHashMap<UUID, List<Turn>>` (Koog concept comment: this is the seam
      step 3/5 replace with real checkpoint storage — link to `docs.koog.ai` persistence page).
- [ ] `agent/AgentService.kt` — `fun chat(accountId: Long, message: String, conversationId: UUID?): AgentChatResult`:
      resolves/creates `conversationId`, renders `ConversationStore` history + new message into
      one input string, runs `AIAgent(anthropicExecutor, anthropicModel).run(input)` — **KDoc +
      inline comment + docs.koog.ai link on the `AIAgent` construction** — catches any
      exception and retries via `AIAgent(openAIExecutor, openAiFallbackModel).run(input)`,
      appends the turn to `ConversationStore`, returns `AgentChatResult(reply, conversationId)`.
- [ ] `common/DomainExceptions.kt` — add `AgentUnavailableException` (both providers fail) →
      mapped to `503` in the global advice.
- Files: `AgentModelProperties.kt`, `ConversationStore.kt`, `AgentService.kt`, exception addition.

### Step 3: (No separate infra/adapter layer)
The auto-configured `anthropicExecutor`/`openAIExecutor` beans from the Koog Spring Boot
starter **are** the adapter layer; nothing extra for step 2 (mirrors step 1's plan, which
also had no separate infra step).

### Step 4: API / presentation layer (FR-07)
- [ ] `agent/AgentController.kt` — `POST /api/v1/agent/chat` (`suspend fun`, matching Koog's
      own documented pattern for calling suspend `PromptExecutor`/`AIAgent` APIs from Spring
      MVC); body `ChatRequest(message: String, conversationId: UUID? = null)`, sender from
      `X-User-Id`; → `200 ChatResponse(reply: String, conversationId: UUID)`.
- [ ] DTOs: `ChatRequest`, `ChatResponse`.
- [ ] `common/GlobalExceptionHandler.kt` — add the `AgentUnavailableException` → `503`
      `ProblemDetail` mapping.
- [ ] `common/OpenApiConfig.kt` — no change needed (springdoc picks up the new controller
      automatically); verify Swagger UI lists `/api/v1/agent/chat`.
- Files: `AgentController.kt`, DTOs, advice update.

### Step 5: Tests (FR-06; AC-09, AC-10, AC-28)
- [ ] **Unit** `AgentServiceTest` (using `ai.koog:agents-test`'s `getMockExecutor`/
      `mockLLMAnswer`): happy path returns a reply + stable `conversationId`; a second call
      with the same `conversationId` sees prior-turn context in the rendered input (assert via
      a captured prompt/spy); Anthropic mock throws → OpenAI mock still answers (**AC-28**:
      the fallback test explicitly fails the Anthropic client and asserts the OpenAI path
      completes the same request).
- [ ] **Context/smoke test** `AgentAutoConfigurationTest` (`@SpringBootTest`, dummy
      `ANTHROPIC_API_KEY`/`OPENAI_API_KEY` test env values): context loads and
      `multiLLMPromptExecutor` bean is present and non-null — proves **AC-09**.
- [ ] **Integration** `AgentControllerTest` (`@SpringBootTest` + MockMvc, mock executor beans
      substituted via `@TestConfiguration`/`@Primary` so no real LLM call happens): `POST
      /api/v1/agent/chat` returns `200` with a reply body and a `conversationId` — proves
      **AC-10**; Swagger/`/v3/api-docs` includes the new endpoint.
- Files: `src/test/kotlin/dev/aparikh/moneytransfer/agent/**`.

### Step 6: Documentation (AC-26, AC-27, AC-29)
- [ ] Add the Koog teaching comments (KDoc + inline `//` + `docs.koog.ai` links) at every
      Koog call site introduced this step: `AIAgent` construction, the auto-configured
      executor bean injection points, and `ConversationStore` (as the pre-checkpoint seam).
- [ ] `README.md` — add "Step 2: AI Agent Chat" section: required env vars, how to start the
      app, and an example `curl -X POST /api/v1/agent/chat -H "X-User-Id: 1" -d '{"message":"..."}'`
      request/response pair.
- Files: touched source files (comments only), `README.md`.

## Acceptance Criteria Mapping
| AC | Verified By |
|----|-------------|
| AC-09: Koog starter starts, both providers configured, `multiLLMPromptExecutor` bean available | `AgentAutoConfigurationTest#contextLoadsWithMultiLlmExecutor` |
| AC-10: `POST /api/v1/agent/chat` returns an LLM text reply (no tools yet) | `AgentControllerTest#chatReturnsReplyAndConversationId` |
| AC-25: every Koog-related version lives in `gradle/libs.versions.toml` | manual review of `build.gradle.kts` (no inline versions) |
| AC-26: Koog call sites carry teaching comments | manual review — Step 6 |
| AC-28 (partial — proven at this step, exercised again at step 3+): Anthropic error → OpenAI `gpt-5.4` fallback completes the same prompt | `AgentServiceTest#fallsBackToOpenAiWhenAnthropicErrors` |
| AC-29: README gets a step-2 usage section | manual review of `README.md` diff |

## Risks & Mitigations
- **Koog Spring Boot starter is `1.0.0-beta`:** API surface may shift before GA. →
  Pin the exact version in the catalog; re-verify bean names/config properties against
  Koog's changelog before starting step 3.
- **Spec model names don't exist as Koog enum entries** (`Sonnet 5`, `Opus 4.8`): → mapped to
  the closest available entries (`Sonnet_4_6`, `Opus_4_7`) via configurable properties, so a
  future Koog release with true `Sonnet_5`/`Opus_4_8` entries is a config change only. `gpt-5.4`
  maps exactly to `GPT5_4`, no substitution needed there.
- **Koog's automatic fault tolerance stops at "same provider, transient error":**
  `RetryingLLMClient` (already wired into every auto-configured executor) retries 429/5xx/
  timeout-shaped errors on the *same* provider with backoff; `MultiLLMPromptExecutor`'s
  `fallback` only triggers on a *missing* client, not on a client that retried and still
  failed. → `AgentService` hand-rolls the cross-provider try/catch instead of relying on
  `FallbackPromptExecutorSettings` for it. Test AC-28 by making the mocked Anthropic
  executor fail in a way that exhausts retry (or isn't retryable at all, e.g. an auth
  error), not just a single transient error `RetryingLLMClient` would absorb on its own.
- **No documented multi-turn `AIAgent` + Spring pattern:** → conversation history is replayed
  into a single input string via `ConversationStore` (our own design, documented above); if a
  later Koog release adds an official session/continuation API, this is the file to revisit,
  not the controller contract.
- **`app.agent.anthropic-complex-model` currently has no trigger:** unused until step 4's
  custom strategy. → wired now via `AgentModelProperties` so step 4 only adds the routing
  logic, not new config plumbing; flagged here so it isn't mistaken for a step-2 bug.
- **Coroutines in a servlet (Spring MVC, not WebFlux) app:** `AIAgent.run`/`PromptExecutor.execute`
  are `suspend` functions. → use `suspend fun` controller methods directly (Spring MVC 6.1+
  supports this natively), matching Koog's own documented sample controller — no manual
  `runBlocking` needed.
- **Secrets:** `ANTHROPIC_API_KEY`/`OPENAI_API_KEY` only via env vars, never committed;
  `AgentAutoConfigurationTest` uses obviously-fake test values.

## Estimated Complexity
**Medium.** The Spring wiring itself is small (one starter dependency + two config keys),
but the step carries two non-trivial, spec-driven design decisions with no official Koog
sample to copy: the hand-rolled error-fallback (Koog's built-in fallback doesn't do what
the spec asks) and in-memory conversation continuity ahead of step 5's real checkpointing.
