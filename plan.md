# Implementation Plan: Observability (Step 6 — `step6-observability`)

> Scope: **Step 6** of `feature.md` (FR-15; AC-19 / AC-19b / AC-19c). Instrument agent execution
> and the service layer with OpenTelemetry, exporting via OTLP to the Grafana **LGTM** stack
> (`grafana/otel-lgtm`) run from docker-compose. Branch `step6-observability` off `step5-persistence`.

## Overview
Two tracing layers, one OTLP destination:
- **Agent (Koog):** install Koog's `OpenTelemetry` feature in the shared `AgentService.runAgent`
  builder (next to `handleEvents`/`ChatMemory`/`Persistence`). It intercepts the pipeline at
  agent/strategy/**node**/**subgraph**/LLM/tool granularity, so every LLM and tool call is a span.
  Because it hooks the *pipeline, not the strategy*, a future custom graph gets node/subgraph spans
  for free — no rework.
- **Service (Spring):** Actuator + Micrometer Tracing (OTel bridge) export HTTP + JDBC spans to the
  same OTLP endpoint with shared resource attributes.

Export is **additive and optional**: with no endpoint configured (tests, plain runs) it is a no-op,
so nothing about the app's behavior depends on the LGTM stack being up.

## Pre-implementation research (verified against Koog 1.0.0 source)
- **Koog OTel feature is transitive** via `koog-agents` (`koog-agents/build.gradle.kts:79` lists
  `agents-features-opentelemetry` in the *included* set) — **no new Koog dependency**. Package
  `ai.koog.agents.features.opentelemetry.feature`.
- **Install API** (`OpenTelemetryConfig`, JVM): `install(OpenTelemetry) { setServiceInfo(name,
  version); addResourceAttributes(map); addSpanExporter(exporter: io.opentelemetry…SpanExporter);
  addSpanProcessor { … } }`. It builds its own `SdkTracerProvider` from the added exporters.
- **Pipeline coverage** (`OpenTelemetry.kt`): intercepts `interceptAgentStarting/Completed/Failed`,
  `interceptStrategyStarting/Completed`, `interceptNodeExecution*`, `interceptSubgraphExecution*`,
  `interceptLLMCall*`, `interceptToolCall*` — strategy-agnostic, so it forward-composes with step 7+.
- **Exporter is standard OTel SDK**: an `io.opentelemetry:opentelemetry-exporter-otlp` artifact
  (`OtlpGrpcSpanExporter`, gRPC :4317) — a NEW dependency; align its version with Koog's transitive
  OTel SDK via `io.opentelemetry:opentelemetry-bom`.

## Architecture Decisions
- **Install site:** in `runAgent` (the one shared builder), so it covers today's `singleRunStrategy`
  and any future graph identically. Teaching comment + `docs.koog.ai` link at the call site (AC-26).
- **Exporter as a bean, endpoint-gated:** `@Bean SpanExporter` returns an `OtlpGrpcSpanExporter` when
  `app.observability.otlp-endpoint` is set, else a **no-op exporter** — so the app boots without LGTM
  and the Testcontainers ITs are unaffected (AC-19c). Tests inject an `InMemorySpanExporter`.
- **Service tracing via Spring:** add `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`;
  Actuator is already present. Configure `management.otlp.tracing.endpoint` and
  `management.tracing.sampling.probability`. HTTP/JDBC spans then export to the same LGTM endpoint.
- **Trace-context scope (honest baseline):** Koog builds its *own* OTel SDK from the injected
  exporter, separate from Spring/Micrometer's. Baseline = **both export to LGTM** and are visible in
  Tempo (satisfies AC-19/AC-19b), but an HTTP span and its agent spans may be *separate traces*.
  Unifying them (agent spans nested under the request span) requires handing Koog the Spring-managed
  `OpenTelemetry` instance — treated as a **stretch goal**, verified during implementation; not
  required by any AC.
- **Versions in the catalog / BOM** (project convention): OTLP exporter + Micrometer bridge coords in
  `libs.versions.toml`; import `opentelemetry-bom` as a platform to keep the SDK/exporter aligned
  with Koog's transitive OTel.
- **Dev infra:** `compose.yaml` adds a `grafana/otel-lgtm` service pinned to a **specific tag** (never
  `latest`), exposing 4317 (OTLP gRPC), 4318 (OTLP HTTP), 3000 (Grafana). Spring Boot Docker Compose
  brings it up in dev only; tests use Testcontainers Postgres and no OTLP.

## Implementation Steps
### Step 1: Dependencies (catalog + build)
- [ ] `libs.versions.toml` + `build.gradle.kts` — add `opentelemetry-exporter-otlp`,
      `micrometer-tracing-bridge-otel`, `platform(opentelemetry-bom)`, and (test)
      `opentelemetry-sdk-testing`. No new Koog dependency (OTel feature is transitive).

### Step 2: Dev infra
- [ ] `compose.yaml` — add `grafana/otel-lgtm:<pinned-tag>` with ports 4317/4318/3000 and a healthcheck.
- Files: `compose.yaml`.

### Step 3: Agent tracing (Koog `OpenTelemetry` feature) — FR-15
- [ ] `agent/AgentObservabilityConfig.kt` — `@Bean SpanExporter` (OTLP when
      `app.observability.otlp-endpoint` set, else no-op) + an `ObservabilityProperties` (service
      name/version, endpoint, sampling).
- [ ] `AgentService.runAgent` — `install(OpenTelemetry) { setServiceInfo(...); addResourceAttributes(...);
      addSpanExporter(spanExporter) }` alongside the existing features, with teaching comments (AC-26).
- Files: `AgentObservabilityConfig.kt`, `AgentService.kt`.

### Step 4: Service tracing (Spring) + config
- [ ] `application.properties` — `management.otlp.tracing.endpoint`,
      `management.tracing.sampling.probability`, `app.observability.*`; keep defaults so unset =
      no-op. README notes how to point at the compose LGTM endpoint.
- Files: `application.properties`.

### Step 5: Tests (AC-19, AC-19c) — no live LGTM
- [ ] `AgentObservabilityTest` — install Koog OTel with an `InMemorySpanExporter` over the
      `agents-test` mock executor, run a turn that calls a tool, assert spans exist for the LLM call
      and the tool call (span names/attributes). (AC-19)
- [ ] Confirm the existing Testcontainers ITs still boot/pass with **no** OTLP endpoint — export is a
      no-op. Optionally a focused assertion that the app context starts with the no-op exporter. (AC-19c)
- Files: `src/test/kotlin/dev/aparikh/moneytransfer/agent/AgentObservabilityTest.kt`.

### Step 6: Docs (AC-26/29, AC-19b)
- [ ] README step-6 section: bring up the LGTM stack, set the endpoint, run a chat, and view the
      agent trace (LLM + tool spans) in Grafana/Tempo — with concrete commands.
- [ ] `docs/notes/observability.md` (optional): the two-layer design, the no-op-without-endpoint
      rule, and the dual-SDK trace-context caveat + the stretch unification.

## Acceptance Criteria Mapping
| AC | Verified By |
|----|-------------|
| AC-19: OTel feature installed; LLM + tool spans emitted | `AgentObservabilityTest#emitsSpansForLlmAndToolCalls` (in-memory exporter) |
| AC-19b: spans visible in Grafana/Tempo via OTLP/LGTM | Manual/dev, documented in README step-6 |
| AC-19c: no endpoint → app + ITs unaffected | Existing Testcontainers ITs pass with no OTLP; no-op exporter bean |

## Risks & Mitigations
- **Dual OTel SDKs (Koog vs Spring) → uncorrelated traces.** → Baseline both export to LGTM (meets
  AC-19/19b); unifying context is a documented stretch, verified at implementation — not an AC.
- **OTel SDK/exporter version skew** with Koog's transitive OTel. → import `opentelemetry-bom` as a
  platform; pin coords in the catalog.
- **Dev-only LGTM must not break tests.** → endpoint-gated no-op exporter; ITs run with no OTLP.
- **`grafana/otel-lgtm:latest` is non-reproducible.** → pin a specific tag (Testcontainers rule).
- **Convention:** step 5's `extra["kotlin-serialization.version"]` already deviates from "no `extra`
  props"; step 6 keeps all new coords in the catalog/BOM and adds no further `extra` props.

## Estimated Complexity
**Medium.** No schema or domain change; the work is one Koog feature install in an existing builder,
an endpoint-gated exporter bean, Spring Micrometer/OTLP wiring, a compose service, and an
in-memory-exporter test. The only real judgment calls are OTel version alignment (BOM) and how far to
push unified trace context (kept as an optional stretch).
