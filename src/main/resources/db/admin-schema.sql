-- =============================================================================
-- Tables OWNED by super-merchant, isolated in the `supermerchant` schema so they
-- never collide with tms-report-java's `public` auth tables (the two services
-- share the tms_report_java database). The JDBC search_path is
-- `supermerchant, public`, so unqualified reads of replicated business tables
-- (transactions, users, tids, terminals, products) fall through to `public`,
-- while super-merchant's own auth/admin tables resolve here.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS supermerchant;

CREATE TABLE IF NOT EXISTS supermerchant.admins (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255),
    email           VARCHAR(255) UNIQUE,
    phone_number    VARCHAR(255),
    password        VARCHAR(255),
    -- Tenant key: the bank this portal user belongs to. NULL = global (IRPay
    -- staff) when combined with the super_admin role. A non-super user with a
    -- bank_code is scoped to that bank's direct merchants.
    bank_code       VARCHAR(20),
    blocked_at      TIMESTAMP,
    blocked_reason  TEXT,
    email_verified_at TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS supermerchant.roles (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    code        VARCHAR(255) UNIQUE,
    description TEXT,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS supermerchant.privileges (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    code        VARCHAR(255) UNIQUE,
    description TEXT,
    modules     JSONB,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS supermerchant.admin_role (
    admin_id    BIGINT NOT NULL REFERENCES supermerchant.admins(id) ON DELETE CASCADE,
    role_id     BIGINT NOT NULL REFERENCES supermerchant.roles(id) ON DELETE CASCADE,
    PRIMARY KEY (admin_id, role_id)
);

CREATE TABLE IF NOT EXISTS supermerchant.role_privilege (
    role_id         BIGINT NOT NULL REFERENCES supermerchant.roles(id) ON DELETE CASCADE,
    privilege_id    BIGINT NOT NULL REFERENCES supermerchant.privileges(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, privilege_id)
);

CREATE TABLE IF NOT EXISTS supermerchant.password_resets (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    token       VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMP NOT NULL,
    created_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS supermerchant.admin_activities (
    id              BIGSERIAL PRIMARY KEY,
    admin_id        BIGINT REFERENCES supermerchant.admins(id) ON DELETE SET NULL,
    action          VARCHAR(255) NOT NULL,
    description     TEXT NOT NULL,
    actionable_type VARCHAR(255),
    actionable_id   BIGINT,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS supermerchant.settings (
    id          BIGSERIAL PRIMARY KEY,
    key         VARCHAR(255) UNIQUE NOT NULL,
    value       TEXT,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS supermerchant.uploads (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    path        VARCHAR(500),
    type        VARCHAR(100),
    size        BIGINT,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS supermerchant.invitations (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255),
    phone_number    VARCHAR(255),
    status          VARCHAR(50) DEFAULT 'pending',
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS supermerchant.otps (
    id          BIGSERIAL PRIMARY KEY,
    identifier  VARCHAR(255),
    token       VARCHAR(255),
    expires_at  TIMESTAMP,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

-- Enrolled tenant banks (the portal's bank registry).
CREATE TABLE IF NOT EXISTS supermerchant.banks (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(20) UNIQUE NOT NULL,
    name          VARCHAR(255),
    contact_email VARCHAR(255),
    status        VARCHAR(20) DEFAULT 'active',
    created_at    TIMESTAMP DEFAULT NOW(),
    updated_at    TIMESTAMP DEFAULT NOW()
);

-- Seed roles
INSERT INTO supermerchant.roles (id, name, code) VALUES
    (1, 'Super Admin', 'super_admin'),
    (2, 'Bank Admin', 'bank_admin'),
    (3, 'Bank Operator', 'bank_operator')
ON CONFLICT (code) DO NOTHING;

-- Seed default global super admin (password: milimatr). bank_code NULL = global.
INSERT INTO supermerchant.admins (id, name, email, password, email_verified_at, created_at, updated_at)
VALUES (1, 'Admin', 'admin@irpay.ng',
        '$2a$10$h.DJQ.4RR6M/ZeN.kLBWr.1xA2gRO5edlbzzpJKWZGrYHreyT2AwG',
        NOW(), NOW(), NOW())
ON CONFLICT (email) DO NOTHING;

INSERT INTO supermerchant.admin_role (admin_id, role_id) VALUES (1, 1)
ON CONFLICT DO NOTHING;

-- Realign sequences after explicit-id seeds (idempotent).
SELECT setval('supermerchant.admins_id_seq', GREATEST((SELECT COALESCE(MAX(id), 1) FROM supermerchant.admins), (SELECT last_value FROM supermerchant.admins_id_seq)), true);
SELECT setval('supermerchant.roles_id_seq',  GREATEST((SELECT COALESCE(MAX(id), 1) FROM supermerchant.roles),  (SELECT last_value FROM supermerchant.roles_id_seq)),  true);
