package dev.aparikh.moneytransfer.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.data.SpanData
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AC-19: the Koog `OpenTelemetry` feature — installed exactly as `AgentService` installs it — emits
 * spans for an agent run and exports them through the provided `SpanExporter`. Uses an in-memory
 * exporter so no OTLP endpoint / LGTM stack is required.
 *
 * The exporter is wrapped in [NonClosingSpanExporter] (the same wrapper production uses) because
 * Koog closes its per-run SDK when the agent finishes (`closeSdks` → `shutdown`), and
 * `InMemorySpanExporter.shutdown()` *clears* its buffer — the wrapper's no-op shutdown preserves the
 * captured spans while still delegating the flush that delivers them.
 */
class AgentObservabilityTest {

    @Test
    fun `installing OpenTelemetry emits and exports spans for an agent run`() {
        val exporter = InMemorySpanExporter.create()
        val executor = getMockExecutor { mockLLMAnswer("done").asDefaultResponse }

        val agent = AIAgent(
            promptExecutor = executor,
            llmModel = AnthropicModels.Sonnet_4_6,
            systemPrompt = "You are a test assistant.",
        ) {
            install(OpenTelemetry) {
                setServiceInfo("money-transfer-agent-test", "0.0.1")
                // NonClosingSpanExporter keeps Koog's per-run SDK teardown from clearing the buffer
                // before we read it (InMemorySpanExporter.shutdown() clears; the wrapper no-ops it).
                addSpanExporter(NonClosingSpanExporter(exporter))
            }
        }

        runBlocking { agent.run("hello") }

        // Spans flush on the run session's SDK teardown via the default (batch) processor, which is
        // asynchronous — poll briefly rather than assume synchronous delivery.
        val spans = awaitSpans(exporter)
        assertFalse(spans.isEmpty(), "expected OpenTelemetry spans to be exported for the agent run")
        assertTrue(
            spans.any { s ->
                listOf("llm", "inference", "agent", "invoke").any { s.name.contains(it, ignoreCase = true) }
            },
            "expected an agent/LLM span; got span names: ${spans.map { it.name }}",
        )
    }

    /** Polls the exporter for up to ~3s while the run's batch span processor flushes asynchronously. */
    private fun awaitSpans(exporter: InMemorySpanExporter): List<SpanData> {
        repeat(30) {
            val spans = exporter.finishedSpanItems
            if (spans.isNotEmpty()) return spans
            Thread.sleep(100)
        }
        return exporter.finishedSpanItems
    }
}
