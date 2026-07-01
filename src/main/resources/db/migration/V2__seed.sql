-- Seed reference data. Account id 1 is the demo user (the default X-User-Id / sender).
-- Accounts 2..6 back the demo user's five contacts; two of them are "Daniel" so that a
-- name lookup for "Daniel" is ambiguous (feature.md AC-04).

INSERT INTO account (id, owner_name, currency, balance) VALUES
    (1, 'Demo User',        'EUR', 1000.00),
    (2, 'Alice Smith',      'EUR',  500.00),
    (3, 'Bob Johnson',      'EUR',  500.00),
    (4, 'Charlie Williams', 'EUR',  500.00),
    (5, 'Daniel Anderson',  'EUR',  500.00),
    (6, 'Daniel Craig',     'EUR',  500.00);

INSERT INTO contact (id, owner_account_id, name, last_name, phone_number, linked_account_id) VALUES
    (1, 1, 'Alice',   'Smith',    '+1-202-555-0101', 2),
    (2, 1, 'Bob',     'Johnson',  '+44-20-7946-0102', 3),
    (3, 1, 'Charlie', 'Williams', '+61-2-5550-0103', 4),
    (4, 1, 'Daniel',  'Anderson', '+49-30-5550-0104', 5),
    (5, 1, 'Daniel',  'Craig',    '+33-1-5550-0105', 6);

-- Advance the identity sequences past the explicitly-inserted seed ids.
SELECT setval(pg_get_serial_sequence('account', 'id'), (SELECT max(id) FROM account));
SELECT setval(pg_get_serial_sequence('contact', 'id'), (SELECT max(id) FROM contact));
