package dev.aparikh.moneytransfer.agent

import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the OpenTelemetry span export for agent tracing (step 6).
 *
 * The Koog `OpenTelemetry` *feature* is installed per-agent in [AgentService.runAgent]; this config
 * provides the single, application-scoped OTLP exporter it ships spans through. Gated by
 * `app.observability.enabled`: when off (the default, and in tests) no exporter bean exists, so
 * `AgentService` receives `null` and installs nothing — export is fully additive.
 */
@Configuration
class AgentObservabilityConfig {

    /**
     * The OTLP gRPC exporter → `grafana/otel-lgtm` collector, app-scoped and closed once on
     * application shutdown (`destroyMethod = "shutdown"`). Koog never shuts it down per run because
     * `shutdownOnAgentClose` is left at its default `false` (see docs/notes/observability.md).
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "app.observability", name = ["enabled"], havingValue = "true")
    fun agentSpanExporter(properties: ObservabilityProperties): SpanExporter =
        OtlpGrpcSpanExporter.builder()
            .setEndpoint(properties.otlpEndpoint)
            .build()

    /**
     * The OTLP gRPC metric exporter → `grafana/otel-lgtm` (Mimir). Koog emits GenAI-convention
     * metrics — token usage (`gen_ai.client.token.usage`), operation latency, tool-call counts.
     * App-scoped, closed once on app shutdown (`destroyMethod = "shutdown"`).
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "app.observability", name = ["enabled"], havingValue = "true")
    fun agentMetricExporter(properties: ObservabilityProperties): MetricExporter =
        OtlpGrpcMetricExporter.builder()
            .setEndpoint(properties.otlpEndpoint)
            .build()
}
