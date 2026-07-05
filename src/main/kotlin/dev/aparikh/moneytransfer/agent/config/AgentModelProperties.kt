package dev.aparikh.moneytransfer.agent.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Model selection for the agent, bound from `app.agent.*`.
 *
 * Values are Koog **model ids** (e.g. `claude-sonnet-4-6`, `gpt-5.4`) rather than Kotlin
 * symbol names, because Koog exposes models as `val`s on an `object` (`AnthropicModels`,
 * `OpenAIModels`) — not as an enum — so they are looked up by id via
 * `LLModelDefinitions.modelsById()` in [AgentService], not by `valueOf`.
 *
 * @property anthropicModel everyday Anthropic model (feature.md's "Sonnet 5" → nearest
 *   available Koog id `claude-sonnet-4-6`).
 * @property openAiFallbackModel OpenAI model used as the cross-provider error-fallback
 *   (feature.md's `gpt-5.4`, an exact Koog id match).
 * @property conversationTtlSeconds how long durable conversation state (Koog's `chat_history` and
 *   `agent_checkpoints`, and our `pending_interaction`) lives before it is eligible for eviction.
 *   Passed as `ttlSeconds` to the Koog JDBC providers and used by the app-side TTL sweep. Default
 *   is 24h — long enough for a real multi-turn confirmation, short enough that abandoned staged
 *   transfers don't linger.
 */
@ConfigurationProperties(prefix = "app.agent")
data class AgentModelProperties(
    val anthropicModel: String = "claude-sonnet-4-6",
    val openAiFallbackModel: String = "gpt-5.4",
    val conversationTtlSeconds: Long = 86_400,
)
