package dev.aparikh.moneytransfer.agent

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aparikh.moneytransfer.common.AgentUnavailableException
import dev.aparikh.moneytransfer.common.GlobalExceptionHandler
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Web-layer test for [AgentController]. [AgentService] is mocked, so the slice needs no LLM,
 * no Postgres, and no Docker — it verifies HTTP wiring, JSON shape (AC-10), and the 503
 * `ProblemDetail` when the assistant is unavailable.
 */
@WebMvcTest(AgentController::class)
@Import(GlobalExceptionHandler::class, AgentControllerTest.Mocks::class)
class AgentControllerTest {

    @TestConfiguration
    class Mocks {
        @Bean
        fun agentService(): AgentService = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var agentService: AgentService

    @Test
    fun `chat returns the reply and conversation id`() {
        val conversationId = UUID.randomUUID()
        every { agentService.chat(1L, "Hi", null) } returns AgentChatResult("Hello!", conversationId)

        mockMvc.perform(
            post("/api/v1/agent/chat")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ChatRequest(message = "Hi"))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reply").value("Hello!"))
            .andExpect(jsonPath("$.conversationId").value(conversationId.toString()))
    }

    @Test
    fun `chat returns 503 problem detail when both providers fail`() {
        every { agentService.chat(any(), any(), any()) } throws AgentUnavailableException(RuntimeException("down"))

        mockMvc.perform(
            post("/api/v1/agent/chat")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ChatRequest(message = "Hi"))),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Assistant unavailable"))
    }
}
