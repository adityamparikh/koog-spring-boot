package dev.aparikh.moneytransfer.agent

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.export.MetricExporter

/**
 * Metric analogue of [NonClosingSpanExporter]. Delegates export/flush and the exporter's aggregation
 * temporality preference (which OTLP cares about), but makes `shutdown()` a no-op so the single,
 * app-scoped OTLP metric exporter survives Koog's per-run SDK teardown (`closeSdks` shuts down the
 * meter provider after every agent run). The real exporter is closed once on app shutdown.
 */
class NonClosingMetricExporter(private val delegate: MetricExporter) : MetricExporter {
    override fun export(metrics: Collection<MetricData>): CompletableResultCode = delegate.export(metrics)
    override fun flush(): CompletableResultCode = delegate.flush()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess() // survive per-run teardown
    override fun getAggregationTemporality(instrumentType: InstrumentType): AggregationTemporality =
        delegate.getAggregationTemporality(instrumentType)
}
