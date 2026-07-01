# Implementation Plan: Money-Transfer Domain (Step 1 — `step1-money-transfer`)

> Scope: **Step 1 only** of `feature.md` — the persisted money-transfer application with
> **no AI**. Covers FR-01…FR-05 and AC-01…AC-08. Koog is intentionally **not** added here
> (it arrives at step 2). Work happens on branch `step1-money-transfer` cut from `master`.
>
> Testing note: this step uses only JUnit 5 + MockK + Testcontainers. Koog's agent testing
> framework (`ai.koog:agents-test` — `getMockExecutor`, `mockTool`, `withTesting()` graph
> assertions) is **deferred to the step 2/3 plans**, where the first agent/tools appear.

## Overview
Realign the Boot-4 skeleton to **Spring Boot 3.5.x**, then build a small, well-tested
money-transfer service: three persisted aggregates (`Account`, `Contact`, `Transfer`) on
**PostgreSQL** via **Spring Data JDBC + Flyway**, a `TransferService` that moves money with
an **atomic conditional UPDATE** (no read-modify-write), and a REST API under `/api/v1`
documented by **springdoc/Swagger UI**. Postgres runs locally through **Spring Boot Docker
Compose support**; integration tests use **Testcontainers**.

## Architecture Decisions
- **Modular-by-feature packages** under `dev.aparikh.moneytransfer`: `account`, `contact`,
  `transfer`, plus `common` (error handling, OpenAPI config). Each feature owns its
  aggregate, repository, service, controller, and DTOs.
- **Spring Data JDBC (not JPA):** aggregates are plain Kotlin `data class`es with `@Id`;
  repositories are `CrudRepository` interfaces. No lazy loading, no persistence context.
- **Concurrency safety = atomic conditional UPDATE:** the debit is a `@Modifying @Query`
  (`UPDATE account SET balance = balance - :amount WHERE id = :id AND balance >= :amount`);
  rows-affected `0` ⇒ insufficient funds. A DB-level `CHECK (balance >= 0)` is a second line
  of defense. No `@Version`, no application retry.
- **Identity model (per feature.md OQ-8/OQ-9):** a person = their `accountId`. `Contact` has
  `ownerAccountId` + `linkedAccountId`. The acting user is passed explicitly via an
  **`X-User-Id`** header (no auth). Surrogate `BIGINT` keys; seed rows use explicit ids so
  contacts can link to accounts.
- **Money = `BigDecimal`** mapped to `NUMERIC(19,2)`; currency EUR throughout.
- **Errors:** domain exceptions → a central `@RestControllerAdvice` returning RFC 7807
  `ProblemDetail`.
- **Immutable ledger:** `Transfer` rows are append-only; `status` starts `COMPLETED`
  (the `REVERSED`/reversal states are defined now but only used at step 7).

## Implementation Steps

### Step 0: Branch & build realignment (Boot 4 → 3.5.x)
- [ ] Create branch `step1-money-transfer` from `master`.
- [ ] `gradle/libs.versions.toml` — make the catalog the single source of truth. Set
      `spring-boot = "3.5.x"` (latest patch), add aliases: `spring-dependency-management`
      plugin, `springdoc` (`springdoc-openapi-starter-webmvc-ui`, latest 2.8.x), and library
      aliases for the starters below (versions Boot-BOM-managed where possible). **Keep** the
      existing `koog`/`kotlin` entries but do **not** reference koog in step 1.
- [ ] `build.gradle.kts`:
  - Plugins: `org.springframework.boot` → **3.5.x**; keep `kotlin("jvm")`,
    `kotlin("plugin.spring")`, `io.spring.dependency-management`. Remove the
    `val koogVersion by extra { … }` line.
  - Dependencies (replace Boot-4 starters): `spring-boot-starter-web`,
    `spring-boot-starter-data-jdbc`, `spring-boot-starter-actuator`,
    `spring-boot-docker-compose` (developmentOnly), `flyway-core`,
    `flyway-database-postgresql`, `postgresql` (runtimeOnly),
    `com.fasterxml.jackson.module:jackson-module-kotlin` (**Jackson 2**, drop
    `tools.jackson.*`), `kotlin-reflect`, `springdoc-openapi-starter-webmvc-ui`.
  - Test deps: `spring-boot-starter-test` (replaces `-webmvc-test`/`-actuator-test`),
    `spring-boot-testcontainers`, `org.testcontainers:postgresql`,
    `org.testcontainers:junit-jupiter`, `mockk`, `junit-platform-launcher`.
  - Keep Java toolchain **25** and `-Xjsr305=strict`.
- [ ] Rename app class `KoogSpringBootApplication` → keep as-is (name is fine) but no koog imports.
- Files: `build.gradle.kts`, `gradle/libs.versions.toml`, (maybe) `gradle-wrapper.properties`.

### Step 1: Database schema & Docker Compose (FR-01, FR-02)
- [ ] `compose.yaml` — `postgres:17` service (db `moneytransfer`, user/password), port 5432.
- [ ] `src/main/resources/db/migration/V1__init.sql`:
  - `account(id BIGSERIAL PK, owner_name TEXT NOT NULL, currency TEXT NOT NULL DEFAULT 'EUR', balance NUMERIC(19,2) NOT NULL CHECK (balance >= 0))`
  - `contact(id BIGSERIAL PK, owner_account_id BIGINT NOT NULL REFERENCES account(id), name TEXT NOT NULL, last_name TEXT, phone_number TEXT, linked_account_id BIGINT NOT NULL REFERENCES account(id))`
  - `transfer(id BIGSERIAL PK, sender_account_id BIGINT NOT NULL REFERENCES account(id), recipient_account_id BIGINT NOT NULL REFERENCES account(id), amount NUMERIC(19,2) NOT NULL CHECK (amount > 0), currency TEXT NOT NULL DEFAULT 'EUR', purpose TEXT, status TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())`
  - Indexes: `contact(owner_account_id)`, `transfer(sender_account_id)`.
- [ ] `V2__seed.sql`: insert accounts with explicit ids + starting balances, then the 5
      contacts (Alice Smith, Bob Johnson, Charlie Williams, Daniel Anderson, Daniel Craig)
      owned by a demo account and linked to recipient accounts.
- [ ] `application.properties`: `spring.flyway.enabled=true`, `spring.jpa`-none,
      `spring.docker.compose.lifecycle-management=start-and-stop`,
      `springdoc.swagger-ui.path=/swagger-ui.html`.
- Files: `compose.yaml`, `db/migration/V1__init.sql`, `V2__seed.sql`, `application.properties`.

### Step 2: Domain layer — aggregates & repositories (FR-01, FR-02, FR-03)
- [ ] `account/Account.kt` — `@Table("account") data class Account(@Id id: Long?, ownerName, currency="EUR", balance: BigDecimal)`.
- [ ] `account/AccountRepository.kt` — `CrudRepository<Account, Long>` with:
  - `@Modifying @Query("UPDATE account SET balance = balance - :amount WHERE id = :id AND balance >= :amount") fun debit(id, amount): Int`
  - `@Modifying @Query("UPDATE account SET balance = balance + :amount WHERE id = :id") fun credit(id, amount): Int`
- [ ] `contact/Contact.kt` — aggregate with `ownerAccountId`, `linkedAccountId`.
- [ ] `contact/ContactRepository.kt` — `CrudRepository`, plus `findByOwnerAccountId(id)` and
      a name-search query (`... WHERE owner_account_id = :id AND lower(name) LIKE lower(:q)`).
- [ ] `transfer/Transfer.kt` — aggregate + `enum TransferStatus { COMPLETED, REVERSED, REVERSAL }`.
- [ ] `transfer/TransferRepository.kt` — `CrudRepository`, `findBySenderAccountIdOrderByCreatedAtDesc(id)`.
- Files: the six files above.

### Step 3: Application/service layer (FR-03, FR-04, FR-05)
- [ ] `transfer/TransferService.kt` — `@Transactional fun transfer(senderAccountId, recipientAccountId, amount, purpose): Transfer`:
      validate `amount > 0`; verify both accounts exist; `debit(...)` → `0` ⇒
      `InsufficientFundsException`; `credit(...)`; save `Transfer(COMPLETED)`.
- [ ] `contact/ContactService.kt` — `getContacts(accountId)`, `findByName(accountId, name)`.
- [ ] `account/AccountService.kt` — `getAccount(id)`, `getBalance(id)` (getBalance is used by
      the API now; the *tool* wrapper comes at step 4).
- [ ] Domain exceptions: `InsufficientFundsException`, `UnknownAccountException`,
      `InvalidAmountException` in `common/`.
- Files: services + exceptions.

### Step 4: (No separate infra/adapter layer)
Spring Data JDBC repositories are the persistence adapter; nothing extra for step 1.

### Step 5: API / presentation layer (FR-05)
- [ ] `transfer/TransferController.kt` — `POST /api/v1/transfers` (body `TransferRequest{recipientAccountId, amount, purpose}`, sender from `X-User-Id`) → `201` `TransferResponse`; `GET /api/v1/transfers` (for `X-User-Id`).
- [ ] `contact/ContactController.kt` — `GET /api/v1/contacts` (list for `X-User-Id`); `GET /api/v1/contacts?name=` (ambiguous lookup → candidates).
- [ ] `account/AccountController.kt` — `GET /api/v1/accounts/{id}`, `GET /api/v1/accounts/{id}/balance`.
- [ ] DTOs: `TransferRequest`, `TransferResponse`, `ContactResponse`, `AccountResponse`, `BalanceResponse`.
- [ ] `common/GlobalExceptionHandler.kt` — `@RestControllerAdvice` mapping domain exceptions
      to `ProblemDetail` (404 unknown account, 422 insufficient funds, 400 invalid amount).
- [ ] `common/OpenApiConfig.kt` — `@OpenAPIDefinition` info/title; Swagger UI at `/swagger-ui.html`.
- Files: three controllers, DTOs, advice, OpenAPI config.

### Step 6: Tests (FR-03; AC-01…AC-08)
- [ ] **Unit** `TransferServiceTest` (MockK): success debits/credits & saves; `amount<=0` →
      `InvalidAmountException`; unknown sender/recipient → `UnknownAccountException`;
      `debit` returns `0` → `InsufficientFundsException` and no credit/save.
- [ ] **Repository slice** `@DataJdbcTest` + Testcontainers (`@ServiceConnection`):
      `debit` succeeds when funds sufficient / returns `0` when not; `credit` adds;
      `ContactRepository` name search returns 2 candidates for "Daniel".
- [ ] **Integration** `@SpringBootTest` + MockMvc + Testcontainers: happy-path transfer
      persists balances; insufficient funds → 422 `ProblemDetail`; contacts list & ambiguous
      lookup; Swagger UI + `/v3/api-docs` reachable.
- [ ] **Concurrency** `TransferConcurrencyIT`: N parallel transfers from one account whose
      total exceeds balance → final balance ≥ 0, exactly the affordable subset succeed, no
      lost updates (real Postgres via Testcontainers).
- [ ] **Persistence-survives-restart** assertion (context reload / re-query) for AC-02.
- Files: `src/test/kotlin/dev/aparikh/moneytransfer/**` + a `TestcontainersConfiguration`.

## Acceptance Criteria Mapping
| AC | Verified By |
|----|-------------|
| AC-01: build passes w/ domain + REST | `./gradlew build` green (all suites) |
| AC-02: transfer persists balances, survives restart | `TransferIntegrationTest#transferPersistsBalances` + re-query |
| AC-03: over-balance rejected, no change | `TransferServiceTest#insufficientFunds`, `TransferIntegrationTest#returns422` |
| AC-04: ambiguous "Daniel" → multiple | `ContactRepositoryTest#findByNameReturnsCandidates`, `ContactControllerTest#ambiguousLookup` |
| AC-05: Swagger + `ProblemDetail` | `OpenApiSmokeTest#apiDocsAndSwaggerUi`, advice tests |
| AC-06: Flyway schema+seed on clean DB | Testcontainers boot (fresh container) in every IT |
| AC-07: concurrent transfers consistent | `TransferConcurrencyIT#noLostUpdatesNoOverdraft` |
| AC-08: unit+integration coverage | whole `src/test` suite |

## Risks & Mitigations
- **Gradle 9.5.1 vs Boot 3.5.x plugin:** the Spring Boot Gradle plugin on 3.5.x may not
  fully support Gradle 9.5.1. → **Verify first**; if it fails, set the wrapper to the latest
  Gradle 8.x supported by Boot 3.5.x.
- **Java 25 + Boot 3.5.x:** confirmed supported by the user, but a given 3.5.x *patch* may
  lag. → Pin the latest 3.5.x patch; if bytecode/toolchain errors appear, confirm the patch
  that added Java 25 support.
- **`debit` rows-affected ambiguity** (0 = insufficient *or* missing account): → check
  account existence **before** `debit`, so a post-existence `0` unambiguously means
  insufficient funds.
- **Flyway seed runs in prod too:** acceptable for a contrived demo; → keep seed in a
  clearly-named `V2__seed.sql` so it can be gated by a Flyway placeholder/profile later.
- **springdoc + Boot 3.5.x version match:** → pin springdoc 2.8.x (Boot-3-compatible);
  verify Swagger UI loads in the integration smoke test.
- **Koog deps lingering from master:** → ensure step-1 `build.gradle.kts` does not reference
  koog libraries, so the module compiles without an OpenAI key.

## Estimated Complexity
**Medium.** The domain and REST surface are small, but the step carries three non-trivial
concerns: the Boot 4→3.5.x realignment, correct atomic-debit concurrency semantics (with a
real-Postgres concurrency test), and Testcontainers/Flyway/docker-compose wiring. None are
deep, but together they make step 1 more than a CRUD skeleton.
