-- Money-transfer domain schema (step 1). Currency is EUR throughout; money is NUMERIC(19,2).

CREATE TABLE account (
    id         BIGSERIAL PRIMARY KEY,
    owner_name TEXT          NOT NULL,
    currency   TEXT          NOT NULL DEFAULT 'EUR',
    balance    NUMERIC(19, 2) NOT NULL CHECK (balance >= 0)
);

-- A contact is an address-book entry owned by one account that points at the
-- recipient's account (linked_account_id). This is the Contact -> Account link
-- the agent tools resolve before any ledger write (feature.md OQ-8).
CREATE TABLE contact (
    id                BIGSERIAL PRIMARY KEY,
    owner_account_id  BIGINT NOT NULL REFERENCES account (id),
    name              TEXT   NOT NULL,
    last_name         TEXT,
    phone_number      TEXT,
    linked_account_id BIGINT NOT NULL REFERENCES account (id)
);

-- Immutable ledger row. status starts COMPLETED; REVERSED/REVERSAL are used at step 7.
CREATE TABLE transfer (
    id                   BIGSERIAL PRIMARY KEY,
    sender_account_id    BIGINT         NOT NULL REFERENCES account (id),
    recipient_account_id BIGINT         NOT NULL REFERENCES account (id),
    amount               NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency             TEXT           NOT NULL DEFAULT 'EUR',
    purpose              TEXT,
    status               TEXT           NOT NULL,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_contact_owner_account_id ON contact (owner_account_id);
CREATE INDEX idx_transfer_sender_account_id ON transfer (sender_account_id);
