package dev.aparikh.moneytransfer.common

import java.math.BigDecimal

/** Thrown when an account referenced by a transfer or lookup does not exist. Maps to HTTP 404. */
class UnknownAccountException(val accountId: Long) :
    RuntimeException("Account $accountId does not exist")

/** Thrown when a sender cannot cover the requested transfer amount. Maps to HTTP 422. */
class InsufficientFundsException(val accountId: Long, val requested: BigDecimal) :
    RuntimeException("Account $accountId has insufficient funds for $requested EUR")

/** Thrown when a transfer amount is not strictly positive. Maps to HTTP 400. */
class InvalidAmountException(val amount: BigDecimal) :
    RuntimeException("Transfer amount must be positive, was $amount")
