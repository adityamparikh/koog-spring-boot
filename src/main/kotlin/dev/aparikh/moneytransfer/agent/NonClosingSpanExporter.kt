package dev.aparikh.moneytransfer.agent

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * A [SpanExporter] that forwards exports/flushes to [delegate] but makes `shutdown()` a **no-op**.
 *
 * Why this exists: we build a fresh `AIAgent` per request, and Koog's `OpenTelemetry` feature builds
 * its own OTel SDK per install and **shuts it down when the agent closes** (`closeSdks()` on
 * agent-close). That teardown would propagate `shutdown()` to the exporter — so a *shared* exporter
 * would be dead after the first request. Wrapping it here lets the single, application-scoped OTLP
 * exporter survive every per-run SDK teardown; the real exporter is closed once on app shutdown
 * (the bean's `destroyMethod`).
 */
class NonClosingSpanExporter(private val delegate: SpanExporter) : SpanExporter {
    override fun export(spans: Collection<SpanData>): CompletableResultCode = delegate.export(spans)
    override fun flush(): CompletableResultCode = delegate.flush()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess() // survive per-run teardown
}
