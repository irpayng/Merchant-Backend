-- =============================================================================
-- Tables OWNED by tms-report-java (not replicated from any microservice)
-- =============================================================================

CREATE TABLE IF NOT EXISTS admins (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255),
    email           VARCHAR(255) UNIQUE,
    phone_number    VARCHAR(255),
    password        VARCHAR(255),
    blocked_at      TIMESTAMP,
    blocked_reason  TEXT,
    email_verified_at TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS roles (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    code        VARCHAR(255) UNIQUE,
    description TEXT,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS privileges (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    code        VARCHAR(255) UNIQUE,
    description TEXT,
    modules     JSONB,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS admin_role (
    admin_id    BIGINT NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    role_id     BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (admin_id, role_id)
);

CREATE TABLE IF NOT EXISTS role_privilege (
    role_id         BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    privilege_id    BIGINT NOT NULL REFERENCES privileges(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, privilege_id)
);

CREATE TABLE IF NOT EXISTS password_resets (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    token       VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMP NOT NULL,
    created_at  TIMESTAMP DEFAULT NOW()
);

-- Seed roles
INSERT INTO roles (id, name, code) VALUES
    (1, 'Super Admin', 'super_admin'),
    (2, 'Compliance', 'compliance'),
    (3, 'Aggregator', 'aggregator'),
    (4, 'Settlement', 'settlement'),
    (5, 'Technical Support', 'support'),
    (6, 'Customer care', 'customer_care'),
    (7, 'Reconciliation', 'reconciliation'),
    (8, 'Audit', 'audit'),
    (9, 'Operations', 'operations'),
    (10, 'Finance', 'finance'),
    (11, 'Admin', 'admin'),
    (12, 'Quality Assurance', 'quality_assurance')
ON CONFLICT (code) DO NOTHING;

-- Seed default super admin (password: milimatr)
INSERT INTO admins (id, name, email, password, email_verified_at, created_at, updated_at)
VALUES (1, 'Admin', 'admin@irpay.ng',
        '$2a$10$h.DJQ.4RR6M/ZeN.kLBWr.1xA2gRO5edlbzzpJKWZGrYHreyT2AwG',
        NOW(), NOW(), NOW())
ON CONFLICT (email) DO NOTHING;

-- Assign super_admin role to default admin
INSERT INTO admin_role (admin_id, role_id) VALUES (1, 1)
ON CONFLICT DO NOTHING;

-- Grant settlement role access to view/manage transactions and to manually
-- credit/debit user wallets (manage_user_wallet gates the manual-funding
-- credit/debit endpoints).
-- Uses a sub-select so this stays idempotent and position-independent
-- (works even if privilege IDs differ across environments).
INSERT INTO role_privilege (role_id, privilege_id)
SELECT r.id, p.id
FROM roles r, privileges p
WHERE r.code = 'settlement'
  AND p.code IN ('view_transaction', 'manage_transaction', 'manage_manual_funding', 'manage_user_wallet')
ON CONFLICT DO NOTHING;

-- Realign sequences after explicit-id seeds.
-- BIGSERIAL only advances on default-driven inserts; the explicit-id seeds
-- above leave each sequence at 1, so the first user-driven insert collides
-- on admins_pkey / roles_pkey. setval() with is_called=true makes nextval()
-- return MAX(id)+1 on the next call, and is a no-op when MAX(id) <= last_value
-- so this stays idempotent across restarts and after additional inserts.
SELECT setval('admins_id_seq',  GREATEST((SELECT COALESCE(MAX(id), 1) FROM admins),  (SELECT last_value FROM admins_id_seq)),  true);
SELECT setval('roles_id_seq',   GREATEST((SELECT COALESCE(MAX(id), 1) FROM roles),   (SELECT last_value FROM roles_id_seq)),   true);

CREATE TABLE IF NOT EXISTS admin_activities (
    id              BIGSERIAL PRIMARY KEY,
    admin_id        BIGINT REFERENCES admins(id) ON DELETE SET NULL,
    action          VARCHAR(255) NOT NULL,
    description     TEXT NOT NULL,
    actionable_type VARCHAR(255),
    actionable_id   BIGINT,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS settings (
    id          BIGSERIAL PRIMARY KEY,
    key         VARCHAR(255) UNIQUE NOT NULL,
    value       TEXT,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS uploads (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    path        VARCHAR(500),
    type        VARCHAR(100),
    size        BIGINT,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS invitations (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255),
    phone_number    VARCHAR(255),
    status          VARCHAR(50) DEFAULT 'pending',
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- Products table — replicated from config service.
-- Created here as fallback in case replication hasn't synced yet.
CREATE TABLE IF NOT EXISTS products (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(255) UNIQUE,
    name            VARCHAR(255),
    status          VARCHAR(50) DEFAULT 'active',
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- Providers table — replicated from config service.
-- Created here as fallback in case replication hasn't synced yet.
CREATE TABLE IF NOT EXISTS providers (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(255) UNIQUE,
    name            VARCHAR(255),
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- Maker-Checker: action checkers (which admins must approve which actions)
CREATE TABLE IF NOT EXISTS action_checkers (
    id              BIGSERIAL PRIMARY KEY,
    action_type     VARCHAR(50) NOT NULL,
    admin_id        BIGINT NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE (action_type, admin_id)
);

-- Maker-Checker: pending approvals queue
CREATE TABLE IF NOT EXISTS pending_approvals (
    id                  BIGSERIAL PRIMARY KEY,
    action_type         VARCHAR(50) NOT NULL,
    payload             JSONB,
    description         TEXT,
    status              VARCHAR(20) NOT NULL DEFAULT 'pending',
    maker_id            BIGINT NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    reject_reason       TEXT,
    rejected_by         BIGINT REFERENCES admins(id) ON DELETE SET NULL,
    required_approvals  INT NOT NULL DEFAULT 0,
    received_approvals  INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW()
);

-- Maker-Checker: individual approval records
CREATE TABLE IF NOT EXISTS approval_records (
    id                  BIGSERIAL PRIMARY KEY,
    pending_approval_id BIGINT NOT NULL REFERENCES pending_approvals(id) ON DELETE CASCADE,
    admin_id            BIGINT NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    created_at          TIMESTAMP DEFAULT NOW(),
    UNIQUE (pending_approval_id, admin_id)
);
