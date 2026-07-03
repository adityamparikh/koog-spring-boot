package dev.aparikh.moneytransfer.agent

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Periodically evicts **abandoned** confirm-gate state (step 5).
 *
 * A staged transfer the user never answers must not linger forever, so this sweep deletes
 * `pending_interaction` rows older than the configured TTL. Koog's own `chat_history` and
 * `agent_checkpoints` tables carry `ttlSeconds` on their providers (filtered on read, evicted by
 * Koog), so only the app-owned table needs an explicit sweep here.
 */
@Component
class ConversationCleanup(
    private val pending: PendingInteractionStore,
    private val properties: AgentModelProperties,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Runs on a fixed delay (default hourly); TTL horizon comes from `app.agent.conversation-ttl-seconds`. */
    @Scheduled(fixedDelayString = "\${app.agent.cleanup-interval-ms:3600000}")
    fun evictAbandonedPending() {
        val cutoff = Instant.now().minusSeconds(properties.conversationTtlSeconds)
        val removed = pending.deleteOlderThan(cutoff)
        if (removed > 0) {
            logger.info("Evicted {} abandoned pending interaction(s) older than {}", removed, cutoff)
        }
    }
}
