-- Async settlement (step 7). A transfer is now a state machine, not a receipt:
--   PENDING → SETTLED   (settler credited the recipient)
--   PENDING → CANCELLED (sender undid it during the settlement window; sender re-credited)
--   PENDING → FAILED    (settlement impossible, e.g. recipient gone; sender re-credited)
-- settle_at is when a PENDING transfer becomes due for settlement; the settler polls on it.
-- The table itself is the settlement work queue (outbox style) — no separate queue table.

ALTER TABLE transfer
    ADD COLUMN settle_at TIMESTAMPTZ;

-- Historical rows predate async settlement; they did debit AND credit atomically, so SETTLED
-- is the truthful terminal state for them. COMPLETED/REVERSED/REVERSAL no longer exist in code.
UPDATE transfer
SET status = 'SETTLED'
WHERE status = 'COMPLETED';

-- The settler's poll: WHERE status = 'PENDING' AND settle_at <= now(). Partial index keeps it
-- cheap no matter how large the settled history grows.
CREATE INDEX idx_transfer_pending_due ON transfer (settle_at) WHERE status = 'PENDING';
