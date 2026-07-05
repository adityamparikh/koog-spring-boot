package dev.aparikh.moneytransfer.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
 * Proves AC-09: with the Koog Spring Boot starter present and both providers configured
 * (dummy keys — no network call happens at bean creation), the app context boots and exposes
 * the auto-configured `multiLLMPromptExecutor` plus the per-provider executor beans.
 * Runs against Testcontainers Postgres (full context); skipped where Docker is unavailable.
 */
@SpringBootTest(
    properties = [
        "ai.koog.anthropic.api-key=test-anthropic-key",
        "ai.koog.openai.api-key=test-openai-key",
    ],
)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AgentAutoConfigurationTest {

    @Autowired
    lateinit var context: ApplicationContext

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
    }

    @Test
    fun `multiLLMPromptExecutor and per-provider executors are auto-configured`() {
        // Assert by bean name: each per-provider executor is itself a MultiLLMPromptExecutor
        // wrapping one client, so a by-type lookup would be ambiguous.
        assertTrue(context.containsBean("multiLLMPromptExecutor"), "multiLLMPromptExecutor bean missing")
        assertTrue(context.containsBean("anthropicExecutor"), "anthropicExecutor bean missing")
        assertTrue(context.containsBean("openAIExecutor"), "openAIExecutor bean missing")
    }
}
