-- Money-transfer domain schema (step 1). Currency is EUR throughout; money is NUMERIC(19,2).

-- An account is a person's profile + wallet, and the single source of truth for their
-- display name and phone (contacts reference it rather than duplicating it).
CREATE TABLE account (
    id           BIGSERIAL PRIMARY KEY,
    first_name   TEXT           NOT NULL,
    last_name    TEXT,
    phone_number TEXT,
    currency     TEXT           NOT NULL DEFAULT 'EUR',
    balance      NUMERIC(19, 2) NOT NULL CHECK (balance >= 0)
);

-- A contact is a directed edge in an owner's address book pointing at another account
-- (contact_account_id), Venmo-"friend" style. Name/phone are NOT stored here — they are read
-- from the linked account. An optional nickname lets the owner label the contact.
CREATE TABLE contact (
    id                 BIGSERIAL PRIMARY KEY,
    owner_account_id   BIGINT NOT NULL REFERENCES account (id),
    contact_account_id BIGINT NOT NULL REFERENCES account (id),
    nickname           TEXT,
    CONSTRAINT uq_contact_owner_target UNIQUE (owner_account_id, contact_account_id)
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
