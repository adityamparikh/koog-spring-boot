package dev.aparikh.moneytransfer.agent.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * History-compression tuning (step 8), bound from `app.agent.history-compression.*`.
 *
 * @property enabled whether the agent's turn strategy compresses history at all. An escape
 *   hatch matching [ObservabilityProperties]'s precedent — compression makes an extra LLM call,
 *   so this can be turned off if that cost/latency isn't wanted.
 * @property maxMessages the message-count threshold (`Prompt.messages.size`) above which the
 *   turn strategy compresses history into retained facts. Deliberately well under
 *   `AgentService.MEMORY_WINDOW` (50) so compression is what normally keeps a conversation
 *   bounded; the `ChatMemory` window is a backstop a healthy conversation should rarely reach.
 */
@ConfigurationProperties(prefix = "app.agent.history-compression")
data class HistoryCompressionProperties(
    val enabled: Boolean = true,
    val maxMessages: Int = 20,
)
