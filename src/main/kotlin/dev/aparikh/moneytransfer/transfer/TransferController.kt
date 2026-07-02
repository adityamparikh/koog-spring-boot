package dev.aparikh.moneytransfer.transfer

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
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

/** A transfer as returned by the API. */
data class TransferResponse(
    val id: Long,
    val senderAccountId: Long,
    val recipientAccountId: Long,
    val amount: BigDecimal,
    val currency: String,
    val purpose: String?,
    val status: TransferStatus,
    val createdAt: Instant,
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
)

/** REST endpoints for creating and listing transfers. */
@RestController
@RequestMapping("/api/v1/transfers")
class TransferController(
    private val transferService: TransferService,
    private val transfers: TransferRepository,
) {

    /** Creates a transfer from the acting user (`X-User-Id`) to the requested recipient account. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: TransferRequest,
    ): TransferResponse =
        transferService.transfer(userId, request.recipientAccountId, request.amount, request.purpose)
            .toResponse()

    /** Lists the acting user's transfers, most recent first. */
    @GetMapping
    fun list(@RequestHeader("X-User-Id") userId: Long): List<TransferResponse> =
        transfers.findBySenderAccountIdOrderByCreatedAtDesc(userId).map { it.toResponse() }
}
