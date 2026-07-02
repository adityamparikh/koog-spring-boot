package dev.aparikh.moneytransfer.common

import java.math.BigDecimal

/** Thrown when an account referenced by a transfer or lookup does not exist. Maps to HTTP 404. */
class UnknownAccountException(val accountId: Long) :
    RuntimeException("Account $accountId does not exist")

/** Thrown when a contact id is not in the acting user's address book. Maps to HTTP 404. */
class UnknownContactException(val contactId: Long) :
    RuntimeException("Contact $contactId does not exist for this user")

/** Thrown when replying to a conversation that has no pending clarification/confirmation. Maps to HTTP 409. */
class NoPendingInteractionException(val conversationId: java.util.UUID) :
    RuntimeException("Conversation $conversationId has nothing awaiting a reply")

/** Thrown when a sender cannot cover the requested transfer amount. Maps to HTTP 422. */
class InsufficientFundsException(val accountId: Long, val requested: BigDecimal) :
    RuntimeException("Account $accountId has insufficient funds for $requested EUR")

/** Thrown when a transfer amount is not strictly positive. Maps to HTTP 400. */
class InvalidAmountException(val amount: BigDecimal) :
    RuntimeException("Transfer amount must be positive, was $amount")

/**
 * Thrown when the agent cannot complete a turn on any configured LLM provider — i.e. the
 * primary (Anthropic) call failed and the OpenAI fallback also failed. Maps to HTTP 503.
 */
class AgentUnavailableException(cause: Throwable) :
    RuntimeException("The assistant is temporarily unavailable", cause)
