-- Seed reference data. Account id 1 is the demo user (the default X-User-Id / sender).
-- Accounts 2..6 are the demo user's five contacts; two are named "Daniel" so that a name
-- lookup for "Daniel" is ambiguous (feature.md AC-04). Names/phones live on the account.

INSERT INTO account (id, first_name, last_name, phone_number, currency, balance) VALUES
    (1, 'Demo',    'User',     '+1-202-555-0100', 'EUR', 1000.00),
    (2, 'Alice',   'Smith',    '+1-202-555-0101', 'EUR',  500.00),
    (3, 'Bob',     'Johnson',  '+44-20-7946-0102', 'EUR',  500.00),
    (4, 'Charlie', 'Williams', '+61-2-5550-0103', 'EUR',  500.00),
    (5, 'Daniel',  'Anderson', '+49-30-5550-0104', 'EUR',  500.00),
    (6, 'Daniel',  'Craig',    '+33-1-5550-0105', 'EUR',  500.00);

-- The demo user's address book: edges to accounts 2..6. One nickname ("Bobby") shows the
-- nickname feature and that lookups match nicknames as well as account names.
INSERT INTO contact (id, owner_account_id, contact_account_id, nickname) VALUES
    (1, 1, 2, NULL),
    (2, 1, 3, 'Bobby'),
    (3, 1, 4, NULL),
    (4, 1, 5, NULL),
    (5, 1, 6, NULL);

-- Advance the identity sequences past the explicitly-inserted seed ids.
SELECT setval(pg_get_serial_sequence('account', 'id'), (SELECT max(id) FROM account));
SELECT setval(pg_get_serial_sequence('contact', 'id'), (SELECT max(id) FROM contact));
