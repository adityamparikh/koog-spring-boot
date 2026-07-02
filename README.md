# Koog Spring Boot — Money-Transfer Assistant

A progressive, tutorial-style build of an agentic money-transfer service. Each step is a
branch that adds one capability (see `feature.md`). **This branch (`step1-money-transfer`)
is step 1: the persisted money-transfer domain, with no AI yet.**

> A full, end-to-end scenario walkthrough for the complete application is delivered on
> completion (after step 10). This README covers how to run and exercise **step 1**.

## Prerequisites
- **JDK 25** (the Gradle toolchain targets Java 25).
- **Docker** — used two ways:
  - at runtime, Spring Boot Docker Compose support auto-starts PostgreSQL from `compose.yaml`;
  - in tests, Testcontainers starts a throwaway PostgreSQL.

## Run
```bash
./gradlew bootRun
```
On startup, Spring Boot brings up the `postgres` service from `compose.yaml`, Flyway applies
the schema (`V1__init.sql`) and seed data (`V2__seed.sql`), and the app listens on
**http://localhost:8080**.

- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **OpenAPI JSON:** http://localhost:8080/v3/api-docs

To run Postgres yourself instead: `docker compose up -d` (then `./gradlew bootRun`).

## Build & test
```bash
./gradlew build
```
Unit tests (MockK) always run. The integration and concurrency tests use Testcontainers and
run **only when Docker is available** (they are skipped otherwise).

## Identity & data model (no auth yet)
A person is identified by their **account id**. The acting user is passed explicitly via the
**`X-User-Id`** header — there is no authentication in step 1. An **`Account`** is a person's
profile **and** wallet (name, phone, balance) — the single source of truth. A **`Contact`** is a
thin **edge** (Venmo-"friend" style): it references another account via `contactAccountId` and
carries an optional `nickname`, but does **not** copy the friend's name/phone. In step 1 you
transfer to an **account id** directly (the agent tools that resolve contacts conversationally
arrive in step 3).

See **[docs/data-model.md](docs/data-model.md)** for the full ER diagram and relationships.

## Seed data
Accounts (profile + wallet):

| Account id | Name | Balance (EUR) | In user 1's contacts? |
|-----------:|------|--------------:|-----------------------|
| 1 | Demo User | 1000.00 | — (the demo sender) |
| 2 | Alice Smith | 500.00 | yes |
| 3 | Bob Johnson | 500.00 | yes — nickname "Bobby" |
| 4 | Charlie Williams | 500.00 | yes |
| 5 | Daniel Anderson | 500.00 | yes (ambiguous) |
| 6 | Daniel Craig | 500.00 | yes (ambiguous) |

The demo user (account `1`) has five contacts — edges to accounts 2–6. Two linked accounts are
named **Daniel**, so a name lookup for "Daniel" is ambiguous; a lookup for "Bob"/"Bobby" matches
account 3 by name or nickname.

## Try the scenarios (curl)
All examples act as the demo user via `-H "X-User-Id: 1"`.

**1. List your contacts**
```bash
curl -s http://localhost:8080/api/v1/contacts -H "X-User-Id: 1"
```

**2. Ambiguous recipient — two "Daniel"s**
```bash
curl -s "http://localhost:8080/api/v1/contacts?name=Daniel" -H "X-User-Id: 1"
# → two contacts; each has a displayName and a contactAccountId — use that as the recipient

```

**3. Check a balance**
```bash
curl -s http://localhost:8080/api/v1/accounts/1/balance   # → 1000.00
```

**4. Happy-path transfer (€100 to Alice's account, id 2)**
```bash
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"recipientAccountId": 2, "amount": 100.00, "purpose": "lunch"}'
# → 201 Created; account 1 → 900.00, account 2 → 600.00
```

**5. Insufficient funds → 422 ProblemDetail**
```bash
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"recipientAccountId": 2, "amount": 5000.00, "purpose": "too much"}'
# → 422 application/problem+json, title "Insufficient funds"; no balance change
```

**6. Unknown account → 404 ProblemDetail**
```bash
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"recipientAccountId": 999, "amount": 10.00, "purpose": null}'
# → 404, title "Account not found"
```

**7. List your transfers (most recent first)**
```bash
curl -s http://localhost:8080/api/v1/transfers -H "X-User-Id: 1"
```

## What's next
Later branches add: Koog framework integration (step 2), agent tools (step 3), a custom
balance-cap strategy (step 4), Postgres checkpointing (step 5), OpenTelemetry (step 6),
transfer rollback (step 7), history compression (step 8), fuller tests (step 9), and a Spring
AI refactor (step 10). See `feature.md` for the full roadmap.
