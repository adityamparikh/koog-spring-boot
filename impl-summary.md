## Implementation Complete — Step 1 (`step1-money-transfer`)

Persisted money-transfer domain, no AI. Realigned Spring Boot 4 skeleton to **3.5.16**.

### Files Created
- `compose.yaml` — local PostgreSQL 17 via Spring Boot Docker Compose support.
- `src/main/resources/db/migration/V1__init.sql` — account/contact/transfer schema (FKs, `CHECK` constraints, indexes).
- `src/main/resources/db/migration/V2__seed.sql` — seed accounts + 5 contacts (two "Daniel"s) + sequence reset.
- `account/Account.kt`, `account/AccountRepository.kt` (atomic `debit`/`credit`), `account/AccountService.kt`, `account/AccountController.kt` (+ DTOs).
- `contact/Contact.kt`, `contact/ContactRepository.kt` (name search), `contact/ContactService.kt`, `contact/ContactController.kt` (+ DTO).
- `transfer/Transfer.kt` (+ `TransferStatus`), `transfer/TransferRepository.kt`, `transfer/TransferService.kt` (`@Transactional`), `transfer/TransferController.kt` (+ DTOs).
- `common/DomainExceptions.kt`, `common/GlobalExceptionHandler.kt` (RFC 7807 `ProblemDetail`), `common/OpenApiConfig.kt`.
- Tests: `transfer/TransferServiceTest.kt` (MockK unit), `MoneyTransferIntegrationTest.kt` + `transfer/TransferConcurrencyIT.kt` (Testcontainers, `disabledWithoutDocker`).

### Files Modified
- `build.gradle.kts` — Boot 3.5.16 plugin, Spring Data JDBC + Flyway + Postgres + springdoc + Testcontainers + MockK; catalog aliases only; dropped Koog/Boot-4 bits; kept Java 25 + `-Xjsr305=strict`.
- `gradle/libs.versions.toml` — version catalog as single source of truth (Boot 3.5.16, springdoc 2.8.17, MockK 1.14.11; Koog entries retained for later steps).
- `src/main/resources/application.properties` — virtual threads, Flyway, springdoc, docker-compose datasource.
- Removed the default `KoogSpringBootApplicationTests.kt` (folded context-load into the Testcontainers IT).

### Acceptance Criteria (all executed against real PostgreSQL via Testcontainers)
- [x] AC-01: Passed — `./gradlew build` green (compiles + all 12 tests).
- [x] AC-02: Passed — `MoneyTransferIntegrationTest#happy path transfer persists updated balances` (reads balances back from Postgres).
- [x] AC-03: Passed — `TransferServiceTest#...insufficient funds...` + `MoneyTransferIntegrationTest#...returns 422...`.
- [x] AC-04: Passed — `MoneyTransferIntegrationTest#...two Daniels` (ambiguous lookup via join to `account`).
- [x] AC-05: Passed — `MoneyTransferIntegrationTest#openapi docs...` + `...422 problem detail`.
- [x] AC-06: Passed — every IT boots Flyway on a clean containerized DB.
- [x] AC-07: Passed — `TransferConcurrencyIT#concurrent transfers never overdraw and never lose updates`.
- [x] AC-08: Passed — 5 unit + 7 integration/concurrency tests, 12/12 green.

### Notes
- **Full suite ran against Docker/Testcontainers** — 12/12 tests pass (unit + integration + concurrency). The `@Testcontainers(disabledWithoutDocker = true)` guard means the build also stays green on machines without Docker (IT skipped).
- **Venmo-style domain model:** `Account` is the profile + wallet (single source of truth for `firstName`/`lastName`/`phoneNumber`); `Contact` is a thin edge `(ownerAccountId, contactAccountId, nickname?)` — no duplicated name/phone (`linkedAccountId` → `contactAccountId`). Contact display is resolved from the linked account; name search joins to it. See `docs/data-model.md` for the ER diagram.
- **Gradle stays on 9.5.1** (the plan floated a downgrade to 8.x): JDK 25 requires Gradle 9, and Boot 3.5.16's plugin applies cleanly on 9.5.1 — so no wrapper change.
- Concurrency safety uses the **atomic conditional UPDATE** (no `@Version`). Existence is checked before the debit so a `0` row-count unambiguously means insufficient funds.
- `plan.md`/`feature.md` live in a separate docs PR; on this branch they are git-excluded reference copies.
