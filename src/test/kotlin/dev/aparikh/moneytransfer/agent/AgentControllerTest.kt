package dev.aparikh.moneytransfer.agent

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aparikh.moneytransfer.common.GlobalExceptionHandler
import dev.aparikh.moneytransfer.common.NoPendingInteractionException
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Web-layer test for [AgentController] with a mocked [AgentService] — verifies HTTP wiring and
 * the tagged responses for `/chat` (CLARIFICATION) and `/reply` (CONFIRMATION / 409). Suspend
 * handlers complete via async dispatch (as in step 2).
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

    private val conversationId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000cc")

    @Test
    fun `chat returns a CLARIFICATION with candidates`() {
        coEvery { agentService.chat(1L, "send money to Daniel", null) } returns ChatResponse(
            reply = "Which Daniel?",
            conversationId = conversationId,
            type = InteractionType.CLARIFICATION,
            candidates = listOf(ContactCandidate(14, "Daniel Anderson", null), ContactCandidate(15, "Daniel Craig", null)),
        )

        val async = mockMvc.perform(
            post("/api/v1/agent/chat")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ChatRequest(message = "send money to Daniel"))),
        ).andExpect(request().asyncStarted()).andReturn()

        mockMvc.perform(asyncDispatch(async))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("CLARIFICATION"))
            .andExpect(jsonPath("$.candidates.length()").value(2))
            .andExpect(jsonPath("$.candidates[1].contactId").value(15))
    }

    @Test
    fun `reply returns a CONFIRMATION summary`() {
        coEvery { agentService.reply(1L, conversationId, "Craig") } returns ChatResponse(
            reply = "Please confirm.",
            conversationId = conversationId,
            type = InteractionType.CONFIRMATION,
            transferSummary = "Send $50 to Daniel Craig",
        )

        val async = mockMvc.perform(
            post("/api/v1/agent/$conversationId/reply")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ReplyRequest(answer = "Craig"))),
        ).andExpect(request().asyncStarted()).andReturn()

        mockMvc.perform(asyncDispatch(async))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("CONFIRMATION"))
            .andExpect(jsonPath("$.transferSummary").value("Send $50 to Daniel Craig"))
    }

    @Test
    fun `reply with nothing pending returns 409 problem detail`() {
        coEvery { agentService.reply(any(), any(), any()) } throws NoPendingInteractionException(conversationId)

        val async = mockMvc.perform(
            post("/api/v1/agent/$conversationId/reply")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ReplyRequest(answer = "yes"))),
        ).andExpect(request().asyncStarted()).andReturn()

        mockMvc.perform(asyncDispatch(async))
            .andExpect(status().isConflict)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("No pending interaction"))
    }
}
