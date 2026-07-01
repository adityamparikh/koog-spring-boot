# Project: Koog Spring Boot — AI Money-Transfer Assistant

## Mission
An AI-agent-driven money-transfer service. Users express money-transfer intents in
natural language (e.g. "send $50 to Alice"), and Koog-powered agents interpret,
validate, and execute the corresponding transfer operations. The application pairs
a conventional Spring Boot backend with the Koog agentic framework (backed by an
OpenAI executor) to turn conversational requests into safe, auditable money movements.

## Tech Stack
- Language: Kotlin 2.3.21 (JVM), targeting Java 25 toolchain
- Framework: Spring Boot 3.5.x — latest stable in the 3.5 line (Spring MVC / servlet stack, Spring Boot Actuator). Chosen over Boot 4 for Koog Spring Boot starter compatibility.
- Build tool: Gradle (Kotlin DSL) with a version catalog (`gradle/libs.versions.toml`) as the single source of truth for all versions and dependency coordinates
- Database: PostgreSQL, provisioned locally via Spring Boot Docker Compose support (`compose.yaml`). Stores domain data (accounts, balances, transfers, contacts) from step 1; Koog checkpoint/agent-state tables are added to the same database at step 5
- Data access: Spring Data JDBC (aggregate roots + `CrudRepository`); no JPA/Hibernate
- Migrations: Flyway (versioned SQL migrations)
- Messaging: none yet
- Testing DB: Testcontainers (PostgreSQL) for integration tests
- AI / Agents: Koog (latest stable) — `koog-spring-boot-starter` plus `koog-agents`, `agents-tools`, `prompt-executor-openai-client`; Spring AI integration (`koog-spring-ai-starter-model-chat`) added in the final step
- JSON: Jackson 2 (`com.fasterxml.jackson.module:jackson-module-kotlin`) — matches Boot 3.5.x
- API docs: springdoc-openapi (version compatible with Boot 3.5.x) with Swagger UI
- Testing: JUnit 5, `kotlin-test-junit5`, `spring-boot-starter-test`, Testcontainers (Postgres, for integration tests)
- Other: `kotlin-reflect`; strict null-safety compiler args (`-Xjsr305=strict`, `-Xannotation-default-target=param-property`)

## Architecture
Modular monolith organized by business feature. Each top-level module lives under the
base package `dev.aparikh.moneytransfer` and contains its own layers (web / service /
domain + Spring Data JDBC repository), rather than grouping the whole app by technical layer.

Example structure:
- `dev.aparikh.moneytransfer.transfer` → transfer web, service, and domain types
- `dev.aparikh.moneytransfer.account` → account web, service, and domain types
- `dev.aparikh.moneytransfer.agent`  → Koog agent configuration, tools, and OpenAI executor wiring

The application entry point is `KoogSpringBootApplication` (`@SpringBootApplication`).

## Conventions
- Package naming: feature-first — `dev.aparikh.moneytransfer.<feature>.<layer>`
- Dependency versions: declared only in `gradle/libs.versions.toml` (the version catalog) — no inline version strings or `extra` properties in `build.gradle.kts`
- REST base path: `/api/v1` — all HTTP endpoints are versioned under this prefix
- Error handling: centralised `@RestControllerAdvice` global exception handler returning a consistent error body (prefer `ProblemDetail` / RFC 7807)
- Dependency injection: constructor injection only; prefer `val` over `var` and `data class` for DTOs (idiomatic immutable Kotlin)
- Null safety: this is a Kotlin project — nullability is explicit in the type system; avoid platform types at boundaries and keep `-Xjsr305=strict` in effect so Java/Spring annotations are honoured
- Authentication: none configured yet — add a security policy before exposing money-moving endpoints

## Approved Dependencies
No explicit constraints were given, so the currently declared dependencies are approved by default. Adding anything outside this list should be flagged first.
- Spring Boot starters: `spring-boot-starter-webmvc`, `spring-boot-starter-actuator`
- Koog: `koog-agents`, `agents-tools`, `prompt-executor-openai-client`
- Kotlin: `kotlin-reflect`, `kotlin-test-junit5`
- JSON: `tools.jackson.module:jackson-module-kotlin`
- Testing: `spring-boot-starter-webmvc-test`, `spring-boot-starter-actuator-test`, `junit-platform-launcher`
