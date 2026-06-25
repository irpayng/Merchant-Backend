-- Seed data for tests (mirrors Laravel's test seeder)

INSERT INTO tiers (id, name, code, created_at, updated_at) VALUES
(1, 'Tier 1', 'tier_1', NOW(), NOW()),
(2, 'Tier 2', 'tier_2', NOW(), NOW()),
(3, 'Tier 3', 'tier_3', NOW(), NOW());

INSERT INTO privileges (id, name, code, created_at, updated_at) VALUES
(1, 'Manage Privilege', 'manage_privilege', NOW(), NOW()),
(2, 'Manage KYC', 'manage_kyc', NOW(), NOW()),
(3, 'Manage User Wallet', 'manage_user_wallet', NOW(), NOW()),
(4, 'Manage User Profile', 'manage_user_profile', NOW(), NOW()),
(5, 'Manage System Configuration', 'manage_system_configuration', NOW(), NOW()),
(6, 'Manage Dispute', 'manage_dispute', NOW(), NOW()),
(7, 'Access Financial Report', 'access_financial_report', NOW(), NOW()),
(8, 'Manage Terminal', 'manage_terminal', NOW(), NOW()),
(9, 'Manage Inventory', 'manage_inventory', NOW(), NOW());

INSERT INTO roles (id, name, code, created_at, updated_at) VALUES
(1, 'Super Admin', 'super_admin', NOW(), NOW()),
(2, 'Admin', 'admin', NOW(), NOW());

-- Super admin gets all privileges
INSERT INTO role_privilege (role_id, privilege_id) VALUES
(1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8), (1, 9);

-- password = 'milimatr' bcrypt-hashed
INSERT INTO admins (id, name, email, phone_number, password, email_verified_at, created_at, updated_at) VALUES
(1, 'Test Admin', 'admin@irpay.ng', '08012345678',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
 NOW(), NOW(), NOW());

INSERT INTO admin_role (admin_id, role_id) VALUES (1, 1);

INSERT INTO statuses (id, name, code, context, created_at, updated_at) VALUES
(1, 'Completed', 'completed', 'success', NOW(), NOW()),
(2, 'Processing', 'processing', 'warning', NOW(), NOW()),
(3, 'Failed', 'failed', 'danger', NOW(), NOW()),
(4, 'Reversed', 'reversed', 'dark', NOW(), NOW());

INSERT INTO products (id, name, code, created_at, updated_at) VALUES
(1, 'Airtime', 'airtime', NOW(), NOW()),
(2, 'Data', 'data', NOW(), NOW()),
(3, 'Transfer', 'transfer', NOW(), NOW());

INSERT INTO providers (id, reference, name, code, active, created_at, updated_at) VALUES
(1, 'prov_ref_1', 'Test Provider', 'test_provider', TRUE, NOW(), NOW()),
(2, 'prov_ref_2', 'Wema', 'wema', TRUE, NOW(), NOW());

INSERT INTO channels (id, reference, name, code, active, created_at, updated_at) VALUES
(1, 'chan_ref_1', 'POS', 'pos', TRUE, NOW(), NOW()),
(2, 'chan_ref_2', 'Mobile', 'mobile', TRUE, NOW(), NOW()),
(3, 'chan_ref_3', 'Web', 'web', TRUE, NOW(), NOW());

INSERT INTO payment_methods (id, reference, name, code, active, created_at, updated_at) VALUES
(1, 'pm_ref_1', 'Wallet', 'wallet', TRUE, NOW(), NOW()),
(2, 'pm_ref_2', 'Card', 'card', TRUE, NOW(), NOW());

INSERT INTO users (id, email, phone_number, type, tier_id, created_at, updated_at) VALUES
(1, 'user1@test.com', '08011111111', 'user', 1, NOW(), NOW()),
(2, 'agent1@test.com', '08022222222', 'agent', 2, NOW(), NOW());

INSERT INTO profiles (id, user_id, first_name, last_name, created_at, updated_at) VALUES
(1, 1, 'John', 'Doe', NOW(), NOW()),
(2, 2, 'Jane', 'Agent', NOW(), NOW());

INSERT INTO wallets (id, walletable_type, walletable_id, type, balance, created_at, updated_at) VALUES
(1, 'users', 1, 'default', 5000.00, NOW(), NOW()),
(2, 'users', 2, 'default', 10000.00, NOW(), NOW()),
(3, 'users', 2, 'commission', 500.00, NOW(), NOW());

INSERT INTO transactions (id, reference, user_id, product_id, provider_id, channel_id, payment_method_id, status_code, amount, created_at, updated_at) VALUES
(1, 'txn_ref_1', 1, 1, 1, 1, 1, 'completed', 1000.00, NOW(), NOW()),
(2, 'txn_ref_2', 1, 2, 1, 2, 1, 'completed', 2000.00, NOW(), NOW()),
(3, 'txn_ref_3', 2, 1, 2, 1, 2, 'failed', 500.00, NOW(), NOW()),
(4, 'txn_ref_4', 2, 3, 1, 3, 1, 'processing', 3000.00, NOW(), NOW());

INSERT INTO statements (id, wallet_id, amount, type, previous_balance, current_balance, description, created_at, updated_at) VALUES
(1, 1, 1000.00, 'debit', 6000.00, 5000.00, 'Airtime purchase', NOW(), NOW()),
(2, 1, 2000.00, 'credit', 3000.00, 5000.00, 'Funding', NOW(), NOW()),
(3, 2, 500.00, 'debit', 10500.00, 10000.00, 'Transfer', NOW(), NOW());

INSERT INTO ledger_accounts (id, category, sub_category, code, name, description, balance, on_credit, on_debit, created_at, updated_at) VALUES
(1, 'asset', 'current_asset', 'cash', 'Cash', 'Cash account', 0, 'increment', 'decrement', NOW(), NOW());

INSERT INTO ledger_entries (id, ledger_id, debit, credit, previous_balance, current_balance, description, created_at, updated_at) VALUES
(1, 1, 1000.00, 0, 0, 1000.00, 'Test debit', NOW(), NOW()),
(2, 1, 0, 500.00, 1000.00, 500.00, 'Test credit', NOW(), NOW());

INSERT INTO admin_activities (id, action, description, admin_id, created_at, updated_at) VALUES
(1, 'login', 'Admin logged in', 1, NOW(), NOW()),
(2, 'update', 'Admin updated a user', 1, NOW(), NOW());

-- Reset identity sequences to avoid conflicts with seeded data
ALTER TABLE admins ALTER COLUMN id RESTART WITH 100;
ALTER TABLE users ALTER COLUMN id RESTART WITH 100;
ALTER TABLE transactions ALTER COLUMN id RESTART WITH 100;
ALTER TABLE wallets ALTER COLUMN id RESTART WITH 100;
ALTER TABLE statements ALTER COLUMN id RESTART WITH 100;
ALTER TABLE roles ALTER COLUMN id RESTART WITH 100;
ALTER TABLE privileges ALTER COLUMN id RESTART WITH 100;
ALTER TABLE products ALTER COLUMN id RESTART WITH 100;
ALTER TABLE providers ALTER COLUMN id RESTART WITH 100;
ALTER TABLE channels ALTER COLUMN id RESTART WITH 100;
ALTER TABLE payment_methods ALTER COLUMN id RESTART WITH 100;
ALTER TABLE statuses ALTER COLUMN id RESTART WITH 100;
ALTER TABLE ledger_accounts ALTER COLUMN id RESTART WITH 100;
ALTER TABLE ledger_entries ALTER COLUMN id RESTART WITH 100;
ALTER TABLE admin_activities ALTER COLUMN id RESTART WITH 100;
ALTER TABLE tiers ALTER COLUMN id RESTART WITH 100;
ALTER TABLE profiles ALTER COLUMN id RESTART WITH 100;
ALTER TABLE configurations ALTER COLUMN id RESTART WITH 100;
