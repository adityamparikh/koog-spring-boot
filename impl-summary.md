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

### Acceptance Criteria
- [x] AC-01: Passed — `./gradlew build` green (compiles + tests).
- [~] AC-02: Covered by `MoneyTransferIntegrationTest#happy path transfer persists updated balances` — **skipped here (no Docker)**; runs where Docker is available. (Durability is via Postgres; the test reads persisted balances back, per-test tx rolls back for isolation.)
- [x] AC-03: Passed (logic) — `TransferServiceTest#transfer with insufficient funds does not credit or record`; also `MoneyTransferIntegrationTest#...returns 422...` (skipped w/o Docker).
- [~] AC-04: Covered by `MoneyTransferIntegrationTest#...two Daniels` — skipped w/o Docker.
- [~] AC-05: Covered by `MoneyTransferIntegrationTest#openapi docs...` + `...422 problem detail` — skipped w/o Docker.
- [~] AC-06: Covered — every Testcontainers IT boots Flyway on a clean DB; skipped w/o Docker.
- [~] AC-07: Covered by `TransferConcurrencyIT#concurrent transfers never overdraw and never lose updates` — skipped w/o Docker.
- [x] AC-08: Unit tests pass (5/5); integration/concurrency tests written & compile, skipped w/o Docker.

Legend: `[x]` executed & passing here · `[~]` implemented and compiling, but skipped in this environment because Docker is unavailable (runs on any machine/CI with Docker).

### Notes
- **Docker unavailable in this environment**, so Testcontainers integration/concurrency tests were skipped (not failed) via `@Testcontainers(disabledWithoutDocker = true)`. Run `./gradlew build` on a machine with Docker to execute AC-02/04/05/06/07 end-to-end.
- **Gradle stays on 9.5.1** (the plan floated a downgrade to 8.x): JDK 25 requires Gradle 9, and Boot 3.5.16's plugin applies cleanly on 9.5.1 — so no wrapper change.
- Concurrency safety uses the **atomic conditional UPDATE** (no `@Version`). Existence is checked before the debit so a `0` row-count unambiguously means insufficient funds.
- `plan.md`/`feature.md` live in a separate docs PR; on this branch they are git-excluded reference copies, so their checkboxes were not propagated here.
