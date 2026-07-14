-- =============================================================================
-- Test data for local development.
--
-- Usage:
--   docker exec -i merchant-db psql -U tms -d merchant < replication/seed-test-data.sql
--
-- Prerequisites: run create-tables.sql first.
-- Assumes merchant user_id = 1 (the seeded dev merchant).
-- =============================================================================

-- ─── Reference data ─────────────────────────────────────────────────────────

INSERT INTO public.products (id, name, code, status, created_at, updated_at)
VALUES
  (1, 'Card Payment', 'card_payment', 'active', NOW(), NOW()),
  (2, 'Bank Transfer', 'bank_transfer', 'active', NOW(), NOW()),
  (3, 'Bill Payment', 'bill_payment', 'active', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.statuses (id, name, code, context, created_at, updated_at)
VALUES
  (1, 'Completed', 'completed', 'transaction', NOW(), NOW()),
  (2, 'Pending', 'pending', 'transaction', NOW(), NOW()),
  (3, 'Failed', 'failed', 'transaction', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.payment_methods (id, name, code, created_at, updated_at)
VALUES
  (1, 'Card', 'card', NOW(), NOW()),
  (2, 'Transfer', 'transfer', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.channels (id, name, code, created_at, updated_at)
VALUES
  (1, 'POS', 'pos', NOW(), NOW()),
  (2, 'Web', 'web', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ─── Terminal ───────────────────────────────────────────────────────────────

INSERT INTO public.terminals (id, user_id, serial, os, model, make, active, locked, created_at, updated_at)
VALUES
  (1, 1, 'TMS00001', 'Android 12', 'P3', 'PAX', true, false, NOW() - INTERVAL '30 days', NOW())
ON CONFLICT (id) DO NOTHING;

-- ─── Transactions (terminal TMS00001, user_id=1) ────────────────────────────

-- Reset sequence to avoid PK conflicts with existing data
SELECT setval('transactions_id_seq', COALESCE((SELECT MAX(id) FROM transactions), 0) + 1, false);

INSERT INTO public.transactions (reference, user_id, product_id, product_code, amount, status_code, status_message, service_fee, agent_commission, channel, payment_method, terminal_id, created_at, updated_at)
SELECT v.* FROM (VALUES
  ('TXN_00001_CARD_001', 1::BIGINT, 1::BIGINT, 'card_payment', 15000.00::NUMERIC, 'completed', 'Approved', 100.00::NUMERIC, 50.00::NUMERIC, 'pos', 'card', 'TMS00001', NOW() - INTERVAL '1 hour', NOW() - INTERVAL '1 hour'),
  ('TXN_00001_CARD_002', 1, 1, 'card_payment', 5000.00, 'completed', 'Approved', 50.00, 25.00, 'pos', 'card', 'TMS00001', NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours'),
  ('TXN_00001_CARD_003', 1, 1, 'card_payment', 25000.00, 'pending', 'Processing', 150.00, 75.00, 'pos', 'card', 'TMS00001', NOW() - INTERVAL '3 hours', NOW() - INTERVAL '3 hours'),
  ('TXN_00001_CARD_004', 1, 1, 'card_payment', 8500.00, 'failed', 'Insufficient funds', 0.00, 0.00, 'pos', 'card', 'TMS00001', NOW() - INTERVAL '4 hours', NOW() - INTERVAL '4 hours'),
  ('TXN_00001_CARD_005', 1, 1, 'card_payment', 42000.00, 'completed', 'Approved', 200.00, 100.00, 'pos', 'card', 'TMS00001', NOW() - INTERVAL '5 hours', NOW() - INTERVAL '5 hours'),
  ('TXN_00001_XFER_001', 1, 2, 'bank_transfer', 10000.00, 'completed', 'Transfer successful', 50.00, 25.00, 'pos', 'transfer', 'TMS00001', NOW() - INTERVAL '6 hours', NOW() - INTERVAL '6 hours'),
  ('TXN_00001_XFER_002', 1, 2, 'bank_transfer', 75000.00, 'completed', 'Transfer successful', 100.00, 50.00, 'pos', 'transfer', 'TMS00001', NOW() - INTERVAL '7 hours', NOW() - INTERVAL '7 hours'),
  ('TXN_00001_CARD_006', 1, 1, 'card_payment', 3200.00, 'completed', 'Approved', 50.00, 25.00, 'pos', 'card', 'TMS00001', NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day'),
  ('TXN_00001_CARD_007', 1, 1, 'card_payment', 18700.00, 'completed', 'Approved', 100.00, 50.00, 'pos', 'card', 'TMS00001', NOW() - INTERVAL '1 day 2 hours', NOW() - INTERVAL '1 day 2 hours'),
  ('TXN_00001_CARD_008', 1, 1, 'card_payment', 55000.00, 'failed', 'Card declined', 0.00, 0.00, 'pos', 'card', 'TMS00001', NOW() - INTERVAL '1 day 4 hours', NOW() - INTERVAL '1 day 4 hours'),
  ('TXN_00001_XFER_003', 1, 2, 'bank_transfer', 120000.00, 'completed', 'Transfer successful', 100.00, 50.00, 'pos', 'transfer', 'TMS00001', NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days'),
  ('TXN_00001_CARD_009', 1, 1, 'card_payment', 6800.00, 'completed', 'Approved', 50.00, 25.00, 'pos', 'card', 'TMS00001', NOW() - INTERVAL '2 days 3 hours', NOW() - INTERVAL '2 days 3 hours'),
  ('TXN_00001_BILL_001', 1, 3, 'bill_payment', 2000.00, 'completed', 'Successful', 30.00, 15.00, 'pos', 'card', 'TMS00001', NOW() - INTERVAL '3 days', NOW() - INTERVAL '3 days'),
  ('TXN_00001_CARD_010', 1, 1, 'card_payment', 90000.00, 'completed', 'Approved', 250.00, 125.00, 'pos', 'card', 'TMS00001', NOW() - INTERVAL '3 days 1 hour', NOW() - INTERVAL '3 days 1 hour'),
  ('TXN_00001_CARD_011', 1, 1, 'card_payment', 1500.00, 'completed', 'Approved', 50.00, 25.00, 'pos', 'card', 'TMS00001', NOW() - INTERVAL '4 days', NOW() - INTERVAL '4 days')
) AS v(reference, user_id, product_id, product_code, amount, status_code, status_message, service_fee, agent_commission, channel, payment_method, terminal_id, created_at, updated_at)
WHERE NOT EXISTS (SELECT 1 FROM public.transactions t WHERE t.reference = v.reference);

-- ─── Disputes ───────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.disputes (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL,
    transaction_reference   VARCHAR(255),
    subject                 VARCHAR(255) NOT NULL,
    message                 TEXT,
    status_code             VARCHAR(20) NOT NULL DEFAULT 'open',
    status_description      VARCHAR(255),
    resolved_at             TIMESTAMPTZ,
    attachment_url          VARCHAR(500),
    created_at              TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.conversations (
    id                      BIGSERIAL PRIMARY KEY,
    dispute_id              BIGINT NOT NULL,
    user_id                 BIGINT,
    sender_type             VARCHAR(20) NOT NULL,
    sender_name             VARCHAR(255),
    message                 TEXT NOT NULL,
    created_at              TIMESTAMPTZ
);

INSERT INTO public.disputes (user_id, transaction_reference, subject, message, status_code, status_description, created_at, updated_at)
SELECT v.* FROM (VALUES
  (1::BIGINT, 'TXN_00001_CARD_003', 'Transaction stuck on pending', 'Customer paid but the transaction has been pending for over 3 hours. Please investigate.', 'open', 'Your dispute has been received and is under review.', NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours'),
  (1, 'TXN_00001_CARD_004', 'Wrong decline reason', 'The customer had sufficient funds but the transaction was declined. They showed me their account balance.', 'open', 'Your dispute has been received and is under review.', NOW() - INTERVAL '3 hours', NOW() - INTERVAL '3 hours'),
  (1, 'TXN_00001_CARD_008', 'Card declined in error', 'This card was declined but works on other terminals. Possible terminal issue.', 'resolved', 'Terminal firmware was updated to fix card read issue.', NOW() - INTERVAL '1 day', NOW() - INTERVAL '6 hours')
) AS v(user_id, transaction_reference, subject, message, status_code, status_description, created_at, updated_at)
WHERE NOT EXISTS (SELECT 1 FROM public.disputes d WHERE d.transaction_reference = v.transaction_reference AND d.user_id = v.user_id);

-- ─── Conversations (dispute messages) ───────────────────────────────────────

INSERT INTO public.conversations (dispute_id, user_id, sender_type, sender_name, message, created_at)
SELECT d.id, v.user_id, v.sender_type, v.sender_name, v.message, v.created_at
FROM (VALUES
  ('TXN_00001_CARD_003', 1::BIGINT, 'user', 'Demo Owner', 'Customer paid but the transaction has been pending for over 3 hours. Please investigate.', NOW() - INTERVAL '2 hours'),
  ('TXN_00001_CARD_003', NULL::BIGINT, 'agent', 'Support', 'We are looking into this. The provider is experiencing delays. We will update you shortly.', NOW() - INTERVAL '1 hour'),
  ('TXN_00001_CARD_004', 1, 'user', 'Demo Owner', 'The customer had sufficient funds but the transaction was declined. They showed me their account balance.', NOW() - INTERVAL '3 hours'),
  ('TXN_00001_CARD_008', 1, 'user', 'Demo Owner', 'This card was declined but works on other terminals. Possible terminal issue.', NOW() - INTERVAL '1 day'),
  ('TXN_00001_CARD_008', NULL::BIGINT, 'agent', 'Support', 'We identified a firmware bug causing intermittent card read failures. A fix has been deployed to your terminal.', NOW() - INTERVAL '8 hours'),
  ('TXN_00001_CARD_008', NULL::BIGINT, 'agent', 'Support', 'Dispute resolved: Terminal firmware was updated to fix card read issue.', NOW() - INTERVAL '6 hours')
) AS v(txn_ref, user_id, sender_type, sender_name, message, created_at)
JOIN public.disputes d ON d.transaction_reference = v.txn_ref
WHERE NOT EXISTS (
  SELECT 1 FROM public.conversations c WHERE c.dispute_id = d.id AND c.message = v.message
);
