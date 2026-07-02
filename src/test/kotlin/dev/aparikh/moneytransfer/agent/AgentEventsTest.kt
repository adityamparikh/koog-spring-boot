package dev.aparikh.moneytransfer.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AC-14: LLM (and tool) calls are observable via Koog's `handleEvents`. This exercises the
 * exact event-handler wiring `AgentService` installs — a `handleEvents` block on an `AIAgent`
 * built over the `agents-test` mock executor — and asserts the LLM callbacks fire during a run.
 */
class AgentEventsTest {

    @Test
    fun `LLM start and completion events fire during an agent run`() {
        val events = mutableListOf<String>()
        val executor = getMockExecutor { mockLLMAnswer("Hello!").asDefaultResponse }

        val agent = AIAgent(
            promptExecutor = executor,
            llmModel = AnthropicModels.Sonnet_4_6,
            systemPrompt = "You are a test assistant.",
        ) {
            handleEvents {
                onLLMCallStarting { events += "llm-start" }
                onLLMCallCompleted { events += "llm-complete" }
            }
        }

        runBlocking { agent.run("hi") }

        assertTrue(events.contains("llm-start"), "onLLMCallStarting did not fire")
        assertTrue(events.contains("llm-complete"), "onLLMCallCompleted did not fire")
    }
}
