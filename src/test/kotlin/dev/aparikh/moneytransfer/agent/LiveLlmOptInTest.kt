package dev.aparikh.moneytransfer.agent

import dev.aparikh.moneytransfer.common.AgentUnavailableException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * The live-LLM fence: API keys alone must not enable paid LLM calls. With both keys set but
 * `app.agent.live-llm` left at its default (`false`, from the main `application.properties`),
 * the Koog starter creates neither provider executor, so a chat turn fails fast with
 * [AgentUnavailableException] (503) instead of reaching Anthropic or OpenAI.
 * The opted-in counterpart is [AgentAutoConfigurationTest].
 */
@SpringBootTest(
    properties = [
        "ai.koog.anthropic.api-key=test-anthropic-key",
        "ai.koog.openai.api-key=test-openai-key",
    ],
)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class LiveLlmOptInTest {

    @Autowired
    lateinit var context: ApplicationContext

    @Autowired
    lateinit var agentService: AgentService

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
    }

    @Test
    fun `keys without the live-llm opt-in configure no provider executor`() {
        assertTrue(context.containsBean("multiLLMPromptExecutor"), "multiLLMPromptExecutor bean missing")
        assertFalse(context.containsBean("anthropicExecutor"), "anthropicExecutor must not exist without the opt-in")
        assertFalse(context.containsBean("openAIExecutor"), "openAIExecutor must not exist without the opt-in")
    }

    @Test
    fun `a chat turn without the opt-in is refused rather than calling a provider`() {
        // Seed account 1 exists (V2__seed.sql), so this fails on the missing providers, not on a 404.
        assertThrows<AgentUnavailableException> {
            runBlocking { agentService.chat(1L, "send 5 dollars to Alice", null) }
        }
    }
}
