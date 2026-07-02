package dev.aparikh.moneytransfer.common

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps domain exceptions to RFC 7807 [ProblemDetail] responses. Returning a `ProblemDetail`
 * lets Spring derive the HTTP status from it.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(UnknownAccountException::class)
    fun handleUnknownAccount(ex: UnknownAccountException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Account not found")
            .apply { title = "Account not found" }

    @ExceptionHandler(InsufficientFundsException::class)
    fun handleInsufficientFunds(ex: InsufficientFundsException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.message ?: "Insufficient funds")
            .apply { title = "Insufficient funds" }

    @ExceptionHandler(InvalidAmountException::class)
    fun handleInvalidAmount(ex: InvalidAmountException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message ?: "Invalid amount")
            .apply { title = "Invalid amount" }

    @ExceptionHandler(AgentUnavailableException::class)
    fun handleAgentUnavailable(ex: AgentUnavailableException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.message ?: "Assistant unavailable")
            .apply { title = "Assistant unavailable" }
}
