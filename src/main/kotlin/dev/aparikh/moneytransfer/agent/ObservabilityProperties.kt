package dev.aparikh.moneytransfer.agent

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Observability settings, bound from `app.observability.*` (step 6).
 *
 * @property enabled master switch — when `false` (the default, and what the tests set) the agent
 *   installs no OpenTelemetry feature and exports nothing, so the app runs identically without the
 *   LGTM stack. Local `bootRun` sets it `true` (docker-compose brings the stack up).
 * @property otlpEndpoint the OTLP gRPC endpoint agent spans are exported to (the `grafana/otel-lgtm`
 *   collector on `:4317` by default).
 * @property serviceName / @property serviceVersion resource attributes tagged on every span so the
 *   agent is identifiable in Grafana/Tempo.
 */
@ConfigurationProperties(prefix = "app.observability")
data class ObservabilityProperties(
    val enabled: Boolean = false,
    val otlpEndpoint: String = "http://localhost:4317",
    val serviceName: String = "money-transfer-agent",
    val serviceVersion: String = "0.0.1",
)
