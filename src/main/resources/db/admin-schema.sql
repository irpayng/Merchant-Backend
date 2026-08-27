-- =============================================================================
-- Tables OWNED by the merchant service (not replicated). The merchant dashboard
-- authenticates its own owner/cashier logins (merchant schema) and keeps a few
-- support tables in the `merchant` schema, isolated from the
-- replicated `public` business tables. The JDBC search_path resolves owned
-- tables here and falls through to `public` for replicated reads.
--
-- The former bank-portal auth model (admins / roles / privileges / banks) was
-- removed in the merchant conversion — see the `merchant` schema below.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS merchant;

CREATE TABLE IF NOT EXISTS merchant.password_resets (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    token       VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMP NOT NULL,
    created_at  TIMESTAMP DEFAULT NOW()
);

-- Activity/audit log. actor_id references a merchant_users login (no FK — the
-- actor may be a system action). Column kept as admin_id for entity mapping
-- compatibility.
CREATE TABLE IF NOT EXISTS merchant.admin_activities (
    id              BIGSERIAL PRIMARY KEY,
    admin_id        BIGINT,
    action          VARCHAR(255) NOT NULL,
    description     TEXT NOT NULL,
    actionable_type VARCHAR(255),
    actionable_id   BIGINT,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS merchant.settings (
    id          BIGSERIAL PRIMARY KEY,
    key         VARCHAR(255) UNIQUE NOT NULL,
    value       TEXT,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS merchant.uploads (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    path        VARCHAR(500),
    type        VARCHAR(100),
    size        BIGINT,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS merchant.invitations (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255),
    phone_number    VARCHAR(255),
    status          VARCHAR(50) DEFAULT 'pending',
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS merchant.otps (
    id          BIGSERIAL PRIMARY KEY,
    identifier  VARCHAR(255),
    token       VARCHAR(255),
    expires_at  TIMESTAMP,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

-- =============================================================================
-- MERCHANT identity — per-merchant dashboard logins (owner + cashiers).
-- A login is bound to a merchant (users.id); a cashier may be locked to one
-- terminal. Onboarding (document upload) captures no password, so accounts
-- start `pending` and are activated via link or OTP (activation_tokens).
-- =============================================================================
CREATE SCHEMA IF NOT EXISTS merchant;

CREATE TABLE IF NOT EXISTS merchant.merchant_users (
    id                BIGSERIAL PRIMARY KEY,
    merchant_id       BIGINT NOT NULL,
    terminal_id       BIGINT,
    role              VARCHAR(20)  NOT NULL DEFAULT 'owner',   -- owner | cashier
    name              VARCHAR(255),
    email             VARCHAR(255) UNIQUE,
    phone_number      VARCHAR(255),
    password          VARCHAR(255),
    status            VARCHAR(20)  NOT NULL DEFAULT 'pending', -- pending | active | revoked
    email_verified_at TIMESTAMP,
    invited_by        BIGINT REFERENCES merchant.merchant_users(id) ON DELETE SET NULL,
    created_at        TIMESTAMP DEFAULT NOW(),
    updated_at        TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS merchant_users_merchant_id_idx ON merchant.merchant_users(merchant_id);

CREATE TABLE IF NOT EXISTS merchant.activation_tokens (
    id                BIGSERIAL PRIMARY KEY,
    merchant_user_id  BIGINT NOT NULL REFERENCES merchant.merchant_users(id) ON DELETE CASCADE,
    token             VARCHAR(255) UNIQUE,        -- link channel (opaque, emailed)
    otp               VARCHAR(10),                -- otp channel (SMS/email code)
    channel           VARCHAR(10) NOT NULL,       -- link | otp
    expires_at        TIMESTAMP NOT NULL,
    consumed_at       TIMESTAMP,
    created_at        TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS activation_tokens_user_idx ON merchant.activation_tokens(merchant_user_id);

-- =============================================================================
-- ROLES & PRIVILEGES — database-driven RBAC for merchant dashboard users.
-- =============================================================================

CREATE TABLE IF NOT EXISTS merchant.privileges (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(100) NOT NULL UNIQUE,
    name        VARCHAR(150) NOT NULL,
    module      VARCHAR(80),
    description VARCHAR(500),
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS merchant.roles (
    id          BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    system_role BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW(),
    UNIQUE(merchant_id, slug)
);

CREATE TABLE IF NOT EXISTS merchant.role_privileges (
    role_id      BIGINT NOT NULL REFERENCES merchant.roles(id) ON DELETE CASCADE,
    privilege_id BIGINT NOT NULL REFERENCES merchant.privileges(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, privilege_id)
);

-- Add role_id FK to merchant_users (nullable for backward compat)
ALTER TABLE merchant.merchant_users ADD COLUMN IF NOT EXISTS role_id BIGINT REFERENCES merchant.roles(id) ON DELETE SET NULL;

-- Unified auth: link staff logins to tms-user operators table.
-- owner logins have operator_id=NULL (they auth via users table).
ALTER TABLE merchant.merchant_users ADD COLUMN IF NOT EXISTS operator_id BIGINT UNIQUE;
CREATE INDEX IF NOT EXISTS idx_merchant_users_operator_id ON merchant.merchant_users(operator_id);


-- =============================================================================
-- AUDIT_LOGS — captures all non-GET HTTP requests by merchant/operator users.
-- Used for security auditing and compliance reporting.
-- =============================================================================
CREATE TABLE IF NOT EXISTS merchant.audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    merchant_id     BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    user_name       VARCHAR(255),
    user_email      VARCHAR(255),
    user_role       VARCHAR(50),
    method          VARCHAR(10) NOT NULL,
    path            VARCHAR(500) NOT NULL,
    action          VARCHAR(255),
    request_body    TEXT,
    response_status INTEGER,
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(500),
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_merchant_id ON merchant.audit_logs(merchant_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id ON merchant.audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON merchant.audit_logs(created_at);
