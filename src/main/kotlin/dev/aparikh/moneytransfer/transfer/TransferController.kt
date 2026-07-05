package dev.aparikh.moneytransfer.transfer

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

/** Request to create a transfer; the sender is taken from the `X-User-Id` header. */
data class TransferRequest(
    val recipientAccountId: Long,
    val amount: BigDecimal,
    val purpose: String? = null,
)

/** A transfer as returned by the API. [settleAt] is when a PENDING transfer settles (undo deadline). */
data class TransferResponse(
    val id: Long,
    val senderAccountId: Long,
    val recipientAccountId: Long,
    val amount: BigDecimal,
    val currency: String,
    val purpose: String?,
    val status: TransferStatus,
    val createdAt: Instant,
    val settleAt: Instant?,
)

private fun Transfer.toResponse() = TransferResponse(
    id = requireNotNull(id),
    senderAccountId = senderAccountId,
    recipientAccountId = recipientAccountId,
    amount = amount,
    currency = currency,
    purpose = purpose,
    status = status,
    createdAt = createdAt,
    settleAt = settleAt,
)

/** REST endpoints for creating, listing, and cancelling transfers. */
@RestController
@RequestMapping("/api/v1/transfers")
class TransferController(
    private val transferService: TransferService,
) {

    /**
     * Initiates a transfer from the acting user (`X-User-Id`): the sender is debited now and the
     * response is `PENDING` — the recipient is credited asynchronously at [TransferResponse.settleAt].
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: TransferRequest,
    ): TransferResponse =
        transferService.initiate(userId, request.recipientAccountId, request.amount, request.purpose)
            .toResponse()

    /** Lists the acting user's transfers (sent and received), most recent first. */
    @GetMapping
    fun list(@RequestHeader("X-User-Id") userId: Long): List<TransferResponse> =
        transferService.transfersFor(userId).map { it.toResponse() }

    /**
     * Cancels the acting user's own PENDING transfer (the undo window). A transfer that already
     * settled is irreversible → 409 `ProblemDetail`.
     */
    @PostMapping("/{id}/cancel")
    fun cancel(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable id: Long,
    ): TransferResponse = transferService.cancel(id, userId).toResponse()
}
