# Side note: observability, and a per-request OTel SDK limitation

> Context: step 6 (`feature.md` FR-15) traces the agent with Koog's `OpenTelemetry` feature →
> OTLP → Grafana LGTM. This note records one real limitation of combining that feature with our
> per-request agent construction, so the choice we made is a documented trade, not an oversight.

## What we ship

`AgentService.runAgent` installs Koog's `OpenTelemetry` feature in the shared agent builder (next to
`ChatMemory`/`Persistence`), exporting **spans** (agent / LLM / tool, → Tempo) and **GenAI metrics**
(token usage, latency, tool-call counts, → Mimir) over OTLP to `grafana/otel-lgtm`. It is gated by
`app.observability.enabled` (on for local `bootRun`, off in tests). Content is **masked by default**
(`isVerbose = false`) — we deliberately never call `setVerbose(true)`, because for a money app that
would emit prompt/response content (amounts, contact names, balances) unmasked into exported spans.

## The limitation: a per-request OTel SDK

Koog's `OpenTelemetry` feature builds **its own OTel SDK per install** (a `BatchSpanProcessor` and a
`PeriodicMetricReader`, each with a background thread). We build a **fresh `AIAgent` per request** (to
capture the acting `accountId`/`conversationId` in the tools), so each request creates a new SDK — and
that SDK is **never shut down**:

- `shutdownOnAgentClose` defaults to **`false`**, so Koog does not close the SDK when a run ends.
- Setting `shutdownOnAgentClose = true` does **not** help: verified empirically, Koog then closes the
  SDK **without flushing the queued spans**, so they are dropped — trading a bounded leak for lost
  telemetry. Worse, not better.

So under sustained load, span/metric processor threads accumulate one set per request — a real, if
slow, resource leak. For this low-traffic tutorial it's immaterial; it would matter in production.

### Why the `NonClosing*` exporter wrappers were removed

An earlier revision wrapped the shared OTLP exporters in `NonClosing{Span,Metric}Exporter` so a
per-run SDK teardown couldn't shut down the app-scoped exporters. Since `shutdownOnAgentClose` is
`false`, that teardown **never fires** — the wrappers guarded a shutdown that doesn't happen, so they
were inert and misleading. Removed. This matches JetBrains' own reference "sandwich" Spring example
(`devoxx-belgium-2025/KoogAgentService.kt`), which uses the same per-agent-SDK + shared-exporter
pattern with no such wrappers.

## The proper fix (deferred to step 7)

The reference example avoids the worst of this by **retaining agents** (one per user, reused across
turns) rather than discarding one per request — so the SDK count is bounded by active conversations,
not total requests. Step 7 restructures the agent for async settlement / rollback and will want
retained, addressable agents anyway (for `getState`/rollback), so the clean fix — **cache the
`AIAgent` per `conversationId`, reuse across turns, evict on TTL** — lands naturally there.

The fully-clean alternative is a **single app-scoped SDK** handed to every install via Koog's
`setSdk(...)`; we didn't take it because that API is on Koog's separate `io.opentelemetry.kotlin`
surface (no Java-OTel bridge), a larger change than this step warrants.
