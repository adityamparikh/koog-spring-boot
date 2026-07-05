package dev.aparikh.moneytransfer.transfer

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Async-settlement tuning (step 7).
 *
 * @property settlementDelay how long a confirmed transfer stays `PENDING` before the settler may
 *   credit the recipient. This **is** the undo window — the "undo send" model. Default 2 minutes;
 *   integration tests shrink it to ~1 s.
 * @property settlementPollMs how often the settler polls for due transfers.
 */
@ConfigurationProperties(prefix = "app.transfer")
data class TransferProperties(
    val settlementDelay: Duration = Duration.ofMinutes(2),
    val settlementPollMs: Long = 5_000,
)
