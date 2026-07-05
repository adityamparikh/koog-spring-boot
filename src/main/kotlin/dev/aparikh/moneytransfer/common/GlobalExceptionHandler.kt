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
    fun handleUnknownAccount(ex: UnknownAccountException) = problem(HttpStatus.NOT_FOUND, "Account not found", ex)

    @ExceptionHandler(InsufficientFundsException::class)
    fun handleInsufficientFunds(ex: InsufficientFundsException) = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds", ex)

    @ExceptionHandler(UnknownContactException::class)
    fun handleUnknownContact(ex: UnknownContactException) = problem(HttpStatus.NOT_FOUND, "Contact not found", ex)

    @ExceptionHandler(NoPendingInteractionException::class)
    fun handleNoPendingInteraction(ex: NoPendingInteractionException) = problem(HttpStatus.CONFLICT, "No pending interaction", ex)

    @ExceptionHandler(InvalidAmountException::class)
    fun handleInvalidAmount(ex: InvalidAmountException) = problem(HttpStatus.BAD_REQUEST, "Invalid amount", ex)

    @ExceptionHandler(AgentUnavailableException::class)
    fun handleAgentUnavailable(ex: AgentUnavailableException) = problem(HttpStatus.SERVICE_UNAVAILABLE, "Assistant unavailable", ex)

    /** RFC 7807 response: [title] names the error; the exception message is the detail (falling back to [title]). */
    private fun problem(status: HttpStatus, title: String, ex: Exception): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, ex.message ?: title).apply { this.title = title }
}
