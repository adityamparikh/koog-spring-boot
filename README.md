# Koog Spring Boot — Money-Transfer Assistant

A progressive, tutorial-style build of an agentic money-transfer service. Each step is a
branch that adds one capability (see `feature.md`). **This branch (`step7-rollback`) is step 7:
transfers now settle asynchronously — confirming debits the sender and queues the transfer as
`PENDING`; a scheduled settler credits the recipient after the settlement window, and until it
does, the transfer can be undone.**

> A full, end-to-end scenario walkthrough for the complete application is delivered on
> completion (after step 10). This README grows one usage section per step; it currently
> covers **step 1** (money-transfer REST), **step 2** (AI agent chat), **step 3** (agent tools &
> human-in-the-loop), **step 4** (balance & overdraft protection), **step 5** (durable
> persistence), **step 6** (OpenTelemetry observability), and **step 7** (async settlement &
> undo).

## Prerequisites
- **JDK 25** (the Gradle toolchain targets Java 25).
- **Docker** — used two ways:
  - at runtime, Spring Boot Docker Compose support auto-starts PostgreSQL from `compose.yaml`;
  - in tests, Testcontainers starts a throwaway PostgreSQL.
- **LLM API keys (step 2+)** — for the `/agent/chat` endpoint, set `ANTHROPIC_API_KEY` and
  `OPENAI_API_KEY` in your environment (never committed). The app still boots without them;
  a chat request just returns `503` until at least one provider is configured. Tests never
  call a real LLM — they use Koog's `agents-test` mock executor.

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

| Account id | Name | Balance (USD) | In user 1's contacts? |
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

**4. Happy-path transfer ($100 to Alice's account, id 2)**
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

**7. List your transfers (sent and received, most recent first — received arrive at step 7 settlement)**
```bash
curl -s http://localhost:8080/api/v1/transfers -H "X-User-Id: 1"
```

## Step 2 — AI agent chat (Koog Spring Boot starter)
Step 2 adds a conversational endpoint backed by the [Koog](https://docs.koog.ai) agent
framework. There are **no tools yet** (those arrive in step 3) — it's a single message in, a
text reply out, keyed by a `conversationId` so follow-up turns keep context.

**Setup:** export your keys before `./gradlew bootRun`:
```bash
export ANTHROPIC_API_KEY=sk-ant-...
export OPENAI_API_KEY=sk-...
```

**LLM configuration.** The Koog starter auto-configures a client + executor per provider whose
`api-key` is non-blank, plus an aggregate `multiLLMPromptExecutor`. Models are selected in
`application.properties` by Koog model id:

| Property | Default | Role |
|----------|---------|------|
| `app.agent.anthropic-model` | `claude-sonnet-4-6` | everyday model (feature.md's "Sonnet 5") |
| `app.agent.anthropic-complex-model` | `claude-opus-4-7` | complex turns (feature.md's "Opus 4.8"); first used in step 4 |
| `app.agent.openai-fallback-model` | `gpt-5.4` | cross-provider error-fallback |

**Multi-provider fault tolerance.** Two layers, by design:
- *Same-provider retry* — each executor already wraps its client in Koog's `RetryingLLMClient`,
  which retries transient `429`/`5xx`/timeout errors on the same provider (backoff + jitter).
- *Cross-provider failover* — following Koog's documented
  [`RobustAIService.generateWithFallback`](https://docs.koog.ai/spring-boot/#llm-provider-fallback)
  pattern, `AgentService` injects the aggregate `multiLLMPromptExecutor` and iterates a model
  fallback chain (`[claude-sonnet-4-6, gpt-5.4]`), calling `execute(prompt, model)` on each in a
  try/catch — the executor routes by `model.provider`, so a failed Anthropic call falls through
  to OpenAI. This is *not* `MultiLLMPromptExecutor.fallback` (`FallbackPromptExecutorSettings`),
  which only routes when a provider's client is **missing**, never on a runtime error. If every
  provider fails, the API returns `503` `ProblemDetail`.

> Implementation notes on the coroutine style of these tests (`runBlocking` vs `runTest`, and
> why `chat` is `suspend`) live in [docs/notes/coroutine-testing.md](docs/notes/coroutine-testing.md).

**8. Chat with the assistant**
```bash
curl -s -X POST http://localhost:8080/api/v1/agent/chat \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"message": "Hi! What can you help me with?"}'
# → 200 {"reply": "...", "conversationId": "b1f0c9e2-..."}
```

**9. Continue the conversation** (pass the returned `conversationId`)
```bash
curl -s -X POST http://localhost:8080/api/v1/agent/chat \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"message": "And remind me what I just asked?", "conversationId": "b1f0c9e2-..."}'
# → the reply reflects the earlier turn (context is replayed per conversationId)
```

## Step 3 — Agent tools & human-in-the-loop
Step 3 gives the agent **tools** (Koog `ToolSet`) that call the step-1 services, so it can
actually list contacts, disambiguate a recipient, and prepare a transfer — with a
human-in-the-loop (HITL) confirmation before any money moves.

- **Tools** (`getContacts`, `chooseRecipient`, `prepareTransfer`) delegate to `ContactService` /
  `TransferService`. The acting account is bound from `X-User-Id` per request, never supplied
  by the LLM.
- **HITL is plain multi-turn conversation.** A turn that asks "which Daniel?" or "confirm?"
  *is* the pause; you answer on the next call. No checkpoint machinery here (that arrives in
  step 5, for surviving restarts).
- **Money never moves without a "yes".** `prepareTransfer` only **stages** a transfer; it executes
  **app-side** only after you affirm via `/reply`. Affirmation is natural-language ("yes",
  "yeah go ahead", "approved") matched by a deterministic phrase interpreter (no LLM on the
  money path); anything ambiguous re-prompts and sends nothing.

Responses are tagged `type`: `ANSWER` | `CLARIFICATION` (pick a `candidates[]` contact) |
`CONFIRMATION` (approve the `transferSummary`). Answer either via `POST /agent/{id}/reply`.

> **See [docs/agent-flow.md](docs/agent-flow.md)** for Mermaid diagrams of the business logic
> and the internal agentic flow, a full HITL sequence diagram, and a longer interaction guide.

**8. Ask the agent to send money to an ambiguous recipient → `CLARIFICATION`**
```bash
curl -s -X POST http://localhost:8080/api/v1/agent/chat \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"message": "send 50 euros to Daniel for dinner"}'
# → {"type":"CLARIFICATION","reply":"Which Daniel …","conversationId":"<id>",
#     "candidates":[{"contactId":14,"displayName":"Daniel Anderson"},
#                   {"contactId":15,"displayName":"Daniel Craig"}]}
```

**9. Reply with the chosen contact → `CONFIRMATION`**
```bash
curl -s -X POST http://localhost:8080/api/v1/agent/<id>/reply \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"answer": "Daniel Craig"}'
# → {"type":"CONFIRMATION","reply":"Please confirm …","conversationId":"<id>",
#     "transferSummary":"Send $50 to Daniel Craig for \"dinner\""}
```

**10. Confirm in natural language → the transfer executes**
```bash
curl -s -X POST http://localhost:8080/api/v1/agent/<id>/reply \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"answer": "yeah go ahead"}'
# → {"type":"ANSWER","reply":"Done — sent $50 to Daniel Craig.","conversationId":"<id>"}
# (reply "no"/"cancel" instead → nothing is sent)
```

## Step 4 — Balance & overdraft protection
Step 4 adds a **`getBalance`** tool and **overdraft protection**: when you ask to send more than
your balance, the agent doesn't fail — it tells you your balance and asks how much you'd like to
send instead (**you pick the amount**, up to your balance). The domain still guarantees safety —
`TransferService` rejects any overdraw atomically — so this is purely a friendlier UX on top.

> Implementation note: this is deliberately **app-side**, not a custom Koog "strategy" graph. The
> over-balance rule is a one-line check, not a control-flow problem; see
> [docs/notes/custom-strategies.md](docs/notes/custom-strategies.md) for what custom strategies
> are actually for and why we skipped one here.

**11. Ask to send more than your balance**
```bash
# 1) Over-balance request. Account 2 (Alice) has $500.
curl -s -X POST http://localhost:8080/api/v1/agent/chat \
  -H "X-User-Id: 2" -H "Content-Type: application/json" \
  -d '{"message": "send 5000 euros to Charlie"}'
# → {"type":"ANSWER","reply":"You have $500 … how much would you like to send, up to $500?",
#     "conversationId":"<id>"}   (nothing is staged — you choose the amount)

# 2) Give a smaller amount. This is a normal conversational turn (/chat, not /reply),
#    because the over-balance prompt left nothing pending — reuse the conversationId.
curl -s -X POST http://localhost:8080/api/v1/agent/chat \
  -H "X-User-Id: 2" -H "Content-Type: application/json" \
  -d '{"message": "send 200 instead", "conversationId": "<id>"}'
# → {"type":"CONFIRMATION","transferSummary":"Send $200 to Charlie Williams", ...}

# 3) Confirm.
curl -s -X POST http://localhost:8080/api/v1/agent/<id>/reply \
  -H "X-User-Id: 2" -H "Content-Type: application/json" \
  -d '{"answer": "yes"}'
# → {"type":"ANSWER","reply":"Done — sent $200 to Charlie Williams.", ...}
```

## Step 5 — Durable persistence (Koog `ChatMemory` + `Persistence`)
Step 5 makes conversation state **survive a restart**, using Koog's built-in constructs backed by
Postgres (Flyway `V3` creates the tables): `ChatMemory` owns the transcript, `Persistence`
checkpoints each run, and the app-side confirm-gate is persisted so a paused "yes/no" isn't lost.
A `GET /{conversationId}/status` endpoint reports how many turns a conversation holds and what (if
anything) it's awaiting.

> The confirm-gate stays **app-side** on purpose — Koog has no cross-turn "pending decision"
> construct, and the irreversible transfer must not live inside the probabilistic agent loop. See
> [docs/notes/persistence.md](docs/notes/persistence.md) for the full design, why each piece maps
> (or doesn't) onto Koog, and two dependency gotchas (a kotlinx-serialization pin and a Jackson
> `@JsonIgnore`) worth knowing before you wire the JDBC providers.

```bash
# Stage a confirmation, then check status BEFORE replying — it's awaiting your "yes".
curl -s -X POST http://localhost:8080/api/v1/agent/chat \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"message": "send 50 to Alice for lunch"}'
# → {"type":"CONFIRMATION","transferSummary":"Send $50 to Alice Smith for \"lunch\"","conversationId":"<id>"}

curl -s http://localhost:8080/api/v1/agent/<id>/status
# → {"conversationId":"<id>","turns":1,"awaiting":"CONFIRMATION"}
# Restart the app here: the pending confirmation is in Postgres, so the reply below still works.

curl -s -X POST http://localhost:8080/api/v1/agent/<id>/reply \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"answer": "yes"}'
# → {"type":"ANSWER","reply":"Done — sent $50 to Alice Smith.","conversationId":"<id>"}
```

## Step 6 — Observability (OpenTelemetry → Grafana LGTM)
Step 6 instruments the agent with **Koog's `OpenTelemetry` feature**: every agent run, LLM call, and
tool call becomes a **span** (→ Tempo), and Koog also emits GenAI **metrics** — token usage, operation
latency, tool-call counts (→ Mimir) — all exported over **OTLP** to Grafana's all-in-one **LGTM**
stack (`grafana/otel-lgtm`). Because the feature hooks the Koog *pipeline* (not the strategy), a
future custom strategy graph will emit per-node/subgraph spans automatically — no rework.

Observability is **additive**: it's controlled by `app.observability.enabled` (on for local `bootRun`,
off in tests), so with no OTLP endpoint the app and the test suite run exactly as before. The
app-scoped OTLP exporter is wrapped (`NonClosingSpanExporter`) so Koog's per-run SDK teardown can't
shut it down between requests.

```bash
# 1) Run the app. Spring Boot Docker Compose starts Postgres AND the LGTM stack (compose.yaml).
./gradlew bootRun

# 2) Send a chat turn (see step 3/4) so the agent runs and emits spans.
curl -s -X POST http://localhost:8080/api/v1/agent/chat \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"message": "who are my contacts?"}'

# 3) Open Grafana → Explore → Tempo and search by service name "money-transfer-agent".
#    You'll see a trace per run with child spans for each LLM call and tool call.
open http://localhost:3000
```

> Point at a different backend by changing `app.observability.otlp-endpoint`. Koog also ships
> one-line adapters for **Langfuse** and **W&B Weave** (`addLangfuseExporter`/`addWeaveExporter`) if
> you prefer an LLM-native trace view over Grafana.

## Step 7 — Async settlement & undo
Step 7 splits the single debit+credit transaction into the two steps real rails have:
**confirming debits the sender immediately and records the transfer as `PENDING`** (funds are
reserved, so your available balance is always honest), and a **scheduled settler credits the
recipient** once the settlement window passes (`app.transfer.settlement-delay`, default 2
minutes). That window **is** the undo window — the "undo send" model:

- A `PENDING` transfer can be cancelled: the sender is refunded, the recipient never sees it.
- A `SETTLED` transfer is **final** — it can never be reversed.
- A racing cancel and settle are decided by one atomic conditional
  `UPDATE … WHERE status = 'PENDING'` — exactly one writer wins, the loser moves no money.

The agent gains two tools — `getRecentTransfers` ("did it go through?") and `undoLastTransfer` —
and undo runs through the **same confirm gate** as sends: the tool only stages; the cancel
executes app-side after your "yes". The confirmation summary now also shows your balance-after,
and the system prompt carries your live balance (both advisory — the atomic debit remains the
only enforcement).

**12. Send, check status, and undo within the window**
```bash
# 1) Send via the agent and confirm (steps 3–5 flow). The reply now says "Queued", not "Done".
curl -s -X POST http://localhost:8080/api/v1/agent/chat \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"message": "send 50 to Alice for lunch"}'
# → {"type":"CONFIRMATION","transferSummary":"Send $50 to Alice Smith for \"lunch\" (balance after: $950.00)", ...}

curl -s -X POST http://localhost:8080/api/v1/agent/<id>/reply \
  -H "X-User-Id: 1" -H "Content-Type: application/json" -d '{"answer": "yes"}'
# → {"type":"ANSWER","reply":"Queued — $50.00 to Alice Smith settles in about 2 minutes; say \"undo\" before then to cancel."}

# 2) Ask for status — the transfer is PENDING until the settler runs.
curl -s -X POST http://localhost:8080/api/v1/agent/chat \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"message": "did my transfer go through?", "conversationId": "<id>"}'
# → mentions the PENDING transfer and when it settles

# 3) Undo it — the cancellation itself needs a confirmation ("yes").
curl -s -X POST http://localhost:8080/api/v1/agent/chat \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"message": "actually, undo that", "conversationId": "<id>"}'
# → {"type":"CONFIRMATION","transferSummary":"Cancel your $50.00 transfer to Alice Smith for \"lunch\"", ...}

curl -s -X POST http://localhost:8080/api/v1/agent/<id>/reply \
  -H "X-User-Id: 1" -H "Content-Type: application/json" -d '{"answer": "yes"}'
# → {"type":"ANSWER","reply":"Done — cancelled the transfer; the money is back in your balance."}
```

**13. After the window, undo is honestly refused**
```bash
# Wait ~2 minutes (or watch getRecentTransfers flip to SETTLED), then:
curl -s -X POST http://localhost:8080/api/v1/agent/chat \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"message": "undo my last transfer"}'
# → "...There is no pending transfer to undo. Settled transfers are final..."
```

**14. The same lifecycle over plain REST**
```bash
# Create: 201 with status PENDING and a settleAt timestamp; sender debited at once.
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"recipientAccountId": 2, "amount": 100.00, "purpose": "rent"}'
# → {"id": <tid>, "status": "PENDING", "settleAt": "...", ...}

# Cancel within the window: 200, status CANCELLED, sender refunded.
curl -s -X POST http://localhost:8080/api/v1/transfers/<tid>/cancel -H "X-User-Id: 1"

# Cancel after settlement instead → 409 application/problem+json,
# title "Transfer not cancellable" — settled is final by design.
```

## What's next
Later branches add: history compression (step 8), fuller tests (step 9),
and a Spring AI refactor (step 10). See `feature.md` for the full roadmap.
