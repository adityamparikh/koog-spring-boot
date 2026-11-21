# Project: Koog Spring Boot — AI Money-Transfer Assistant

## Mission
An AI-agent-driven money-transfer service. Users express money-transfer intents in
natural language (e.g. "send $50 to Alice"), and Koog-powered agents interpret,
validate, and execute the corresponding transfer operations. The application pairs
a conventional Spring Boot backend with the Koog agentic framework (Anthropic primary,
OpenAI as cross-provider fallback) to turn conversational requests into safe, auditable
money movements.

## Tech Stack
- Language: Kotlin 2.3.21 (JVM), targeting Java 25 toolchain
- Framework: Spring Boot 3.5.x — latest stable in the 3.5 line (Spring MVC / servlet stack, Spring Boot Actuator). Chosen over Boot 4 for Koog Spring Boot starter compatibility.
- Build tool: Gradle (Kotlin DSL) with a version catalog (`gradle/libs.versions.toml`) as the single source of truth for all versions and dependency coordinates
- Database: PostgreSQL, provisioned locally via Spring Boot Docker Compose support (`compose.yaml`). Stores domain data (accounts, balances, transfers, contacts) from step 1; Koog checkpoint/agent-state tables are added to the same database at step 5
- Data access: Spring Data JDBC (aggregate roots + `CrudRepository`); no JPA/Hibernate
- Migrations: Flyway (versioned SQL migrations)
- Messaging: none yet
- Observability: Koog's OpenTelemetry feature → OTLP exporter → `grafana/otel-lgtm` (step 6, `compose.yaml`)
- Testing DB: Testcontainers (PostgreSQL) for integration tests
- AI / Agents: Koog 1.0.0 — `koog-spring-boot-starter` (1.0.0-beta) auto-configures a `multiLLMPromptExecutor` over **Anthropic** (`app.agent.anthropic-model`, default `claude-sonnet-4-6`) and **OpenAI** (`app.agent.openai-fallback-model`, default `gpt-5.4`) as the cross-provider error-fallback; `koog-agents` comes in transitively. Provider executors are only created when `app.agent.live-llm=true` (see `AGENTS.md`). Spring AI integration (`koog-spring-ai-starter-model-chat`) is planned for step 10
- JSON: Jackson 2 (`com.fasterxml.jackson.module:jackson-module-kotlin`) — matches Boot 3.5.x
- API docs: springdoc-openapi (version compatible with Boot 3.5.x) with Swagger UI
- Testing: JUnit 5, `spring-boot-starter-test`, MockK, Koog `agents-test` (mock LLM executor), Testcontainers (Postgres, for integration tests)
- Static analysis / formatting: detekt (`config/detekt/detekt.yml`) and Spotless + ktlint (`.editorconfig`), both run by `./gradlew check`
- Other: `kotlin-reflect`; strict null-safety compiler arg `-Xjsr305=strict`

## Architecture
Modular monolith organized by business feature. Each top-level module lives under the
base package `dev.aparikh.moneytransfer` and contains its own layers (web / service /
domain + Spring Data JDBC repository), rather than grouping the whole app by technical layer.

Example structure:
- `dev.aparikh.moneytransfer.transfer` → transfer web, service, and domain types
- `dev.aparikh.moneytransfer.account` → account web, service, and domain types
- `dev.aparikh.moneytransfer.contact` → contact (edge to another account) web, service, and domain types
- `dev.aparikh.moneytransfer.agent`  → `AgentService` / `AgentController` (Koog agent per turn), with
  `agent.tools` (the `MoneyTransferTools` ToolSet), `agent.hitl` (pending clarification/confirmation
  store and affirmation parsing) and `agent.config` (`@ConfigurationProperties` and OTel/persistence beans)
- `dev.aparikh.moneytransfer.common` → domain exceptions, the global `ProblemDetail` handler, OpenAPI config

The application entry point is `MoneyTransferApplication` (`src/main/kotlin/dev/aparikh/moneytransfer/MoneyTransferApplication.kt`, `@SpringBootApplication`).

The domain is modelled Venmo-style: an `Account` is a person's profile + wallet (the single
source of truth for name/phone/balance), a `Contact` is a thin edge referencing another
account, and a `Transfer` is an immutable ledger row between two accounts. See
`docs/data-model.md` for the ER diagram and relationships.

## Conventions
- Package naming: feature-first — `dev.aparikh.moneytransfer.<feature>.<layer>`
- Dependency versions: declared only in `gradle/libs.versions.toml` (the version catalog) — no inline version strings in `build.gradle.kts`. The one deliberate exception is `extra["kotlin-serialization.version"] = "1.9.0"`, which overrides the Spring Boot BOM's kotlinx-serialization (1.6.3) up to what Koog 1.0.0's JDBC providers need; the dependency-management plugin only honours it as an `extra` property
- REST base path: `/api/v1` — all HTTP endpoints are versioned under this prefix
- Error handling: centralised `@RestControllerAdvice` global exception handler returning a consistent error body (prefer `ProblemDetail` / RFC 7807)
- Dependency injection: constructor injection only; prefer `val` over `var` and `data class` for DTOs (idiomatic immutable Kotlin)
- Null safety: this is a Kotlin project — nullability is explicit in the type system; avoid platform types at boundaries and keep `-Xjsr305=strict` in effect so Java/Spring annotations are honoured
- Authentication: none configured yet — add a security policy before exposing money-moving endpoints

## Approved Dependencies
No explicit constraints were given, so the currently declared dependencies are approved by default. Adding anything outside this list should be flagged first.
The list mirrors `gradle/libs.versions.toml`; that file wins if they ever disagree.
- Spring Boot starters: `spring-boot-starter-web`, `spring-boot-starter-data-jdbc`, `spring-boot-starter-actuator`, `spring-boot-docker-compose` (developmentOnly)
- Persistence: `flyway-core`, `flyway-database-postgresql`, `postgresql`
- Koog: `koog-spring-boot-starter`, `agents-features-chat-history-jdbc`, `agents-features-persistence-jdbc`
- Observability: `opentelemetry-bom`, `opentelemetry-exporter-otlp`
- Kotlin: `kotlin-reflect`
- JSON: `com.fasterxml.jackson.module:jackson-module-kotlin` (Jackson 2)
- API docs: `springdoc-openapi-starter-webmvc-ui`
- Testing: `spring-boot-starter-test`, `spring-boot-testcontainers`, `testcontainers` (`postgresql`, `junit-jupiter`), `mockk`, `agents-test`, `opentelemetry-sdk-testing`, `junit-platform-launcher`
- Build plugins: Kotlin (jvm, spring, serialization), Spring Boot, Spring dependency-management, detekt, Spotless
