package dev.aparikh.moneytransfer.agent.config

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.features.chathistory.jdbc.PostgresJdbcChatHistoryProvider
import ai.koog.agents.features.persistence.jdbc.PostgresJdbcPersistenceStorageProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * Wires Koog's two durable storage providers as Spring beans over the app's shared [DataSource]
 * (step 5). Both back a built-in Koog agent feature installed in [AgentService.runAgent]:
 *  - [ChatHistoryProvider] → the `ChatMemory` feature (conversation transcript).
 *  - [PostgresJdbcPersistenceStorageProvider] → the `Persistence` feature (run checkpoints).
 *
 * The providers do **not** create their own tables here — Flyway's `V3__agent_persistence.sql`
 * owns the schema (project convention: `ddl-auto` off, Flyway is the single authority), and the
 * migration's DDL is copied verbatim from Koog's own schema-migrators, so the providers find
 * exactly the tables and columns their SQL expects. `ttlSeconds` lets Koog filter expired rows on
 * read and physically evict them via the TTL sweep.
 */
@Configuration
class AgentPersistenceConfig {

    /** ChatMemory's transcript store: keyed by conversation id (= the run's sessionId). */
    @Bean
    fun chatHistoryProvider(dataSource: DataSource, properties: AgentModelProperties): ChatHistoryProvider =
        PostgresJdbcChatHistoryProvider(dataSource = dataSource, ttlSeconds = properties.conversationTtlSeconds)

    /** Persistence's checkpoint store: intra-run crash recovery, keyed by the same sessionId. */
    @Bean
    fun agentCheckpointStorage(
        dataSource: DataSource,
        properties: AgentModelProperties,
    ): PostgresJdbcPersistenceStorageProvider =
        PostgresJdbcPersistenceStorageProvider(dataSource = dataSource, ttlSeconds = properties.conversationTtlSeconds)
}
