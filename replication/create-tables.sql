-- =============================================================================
-- DDL for tables normally populated via logical replication from the report DB.
-- Schema sourced from staging report cluster (tms_report_java).
--
-- Usage:
--   docker exec -i merchant-db psql -U tms -d merchant < replication/create-tables.sql
-- =============================================================================

-- ─── transactions ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.transactions (
    id                          BIGSERIAL PRIMARY KEY,
    reference                   VARCHAR(40) NOT NULL,
    user_id                     BIGINT NOT NULL,
    product_id                  BIGINT,
    product_code                VARCHAR(50),
    provider_id                 BIGINT,
    provider_code               VARCHAR(50),
    amount                      NUMERIC NOT NULL,
    status_code                 VARCHAR(20) NOT NULL,
    status_message              VARCHAR(255),
    retry_count                 INTEGER,
    service_fee                 NUMERIC,
    amount_to_pay               NUMERIC,
    agent_commission            NUMERIC,
    aggregator_commission       NUMERIC,
    super_aggregator_commission NUMERIC,
    company_commission          NUMERIC,
    channel                     VARCHAR(50),
    payment_method              VARCHAR(50),
    terminal_id                 VARCHAR(50),
    latitude                    DOUBLE PRECISION,
    longitude                   DOUBLE PRECISION,
    location_state              VARCHAR(100),
    location_lga                VARCHAR(100),
    metadata                    JSONB,
    config_context              JSONB,
    custom_charges              JSONB,
    reversal_blob               TEXT,
    claimed_at                  TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ,
    updated_at                  TIMESTAMPTZ
);

-- ─── users ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.users (
    id                          BIGSERIAL PRIMARY KEY,
    email                       VARCHAR(255),
    phone_number                VARCHAR(255),
    password                    VARCHAR(255),
    account_number              VARCHAR(10),
    bvn                         VARCHAR(255),
    fcm_token                   VARCHAR(255),
    type                        VARCHAR(255) DEFAULT 'user',
    tier_id                     BIGINT,
    parent_id                   BIGINT,
    onboarding_id               BIGINT,
    device_id                   VARCHAR(255),
    bvn_photo_url               VARCHAR(255),
    selfie_url                  VARCHAR(255),
    business_name               VARCHAR(255),
    aggregator_code             VARCHAR(16),
    aggregator_device_id        VARCHAR(255),
    bills_enabled               BOOLEAN,
    email_verified_at           TIMESTAMPTZ,
    frozen_at                   TIMESTAMPTZ,
    suspended_at                TIMESTAMPTZ,
    suspended_by                VARCHAR(255),
    suspended_by_type           VARCHAR(20),
    suspended_reason            VARCHAR(500),
    blocked_at                  TIMESTAMPTZ,
    blocked_by                  VARCHAR(255),
    blocked_reason              VARCHAR(500),
    device_changed_at           TIMESTAMPTZ,
    aggregator_device_changed_at TIMESTAMPTZ,
    deleted_at                  TIMESTAMPTZ,
    deletion_requested_at       TIMESTAMPTZ,
    deletion_scheduled_for      TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ,
    updated_at                  TIMESTAMPTZ
);

-- ─── profiles ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.profiles (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    first_name      VARCHAR(255) NOT NULL,
    last_name       VARCHAR(255) NOT NULL,
    middle_name     VARCHAR(255),
    gender          VARCHAR(10),
    date_of_birth   DATE,
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ
);

-- ─── terminals ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.terminals (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT,
    serial                  VARCHAR(255) NOT NULL,
    os                      VARCHAR(255),
    model                   VARCHAR(255) NOT NULL,
    make                    VARCHAR(255) NOT NULL,
    bank_code               VARCHAR(20),
    active                  BOOLEAN NOT NULL,
    locked                  BOOLEAN NOT NULL DEFAULT false,
    lock_message            VARCHAR(500),
    locked_at               TIMESTAMPTZ,
    locked_by               VARCHAR(255),
    secret_key              VARCHAR(255),
    last_seen_at            TIMESTAMPTZ,
    last_seen_battery_pct   INTEGER,
    last_seen_lat           NUMERIC,
    last_seen_lng           NUMERIC,
    last_seen_signal        VARCHAR(16),
    created_at              TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ
);

-- ─── terminal_metrics ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.terminal_metrics (
    id                          BIGSERIAL PRIMARY KEY,
    terminal_id                 BIGINT NOT NULL,
    serial                      VARCHAR(64) NOT NULL,
    model                       VARCHAR(64),
    vendor                      VARCHAR(64),
    os_version                  VARCHAR(32),
    sdk_version                 VARCHAR(32),
    firmware_version            VARCHAR(64),
    kernel_version              VARCHAR(64),
    app_version                 VARCHAR(32),
    battery_pct                 INTEGER,
    battery_temp_c              NUMERIC,
    battery_voltage_mv          INTEGER,
    battery_plugged             BOOLEAN,
    battery_health              VARCHAR(32),
    ram_total_bytes             BIGINT,
    ram_avail_bytes             BIGINT,
    storage_total_bytes         BIGINT,
    storage_avail_bytes         BIGINT,
    network_type                VARCHAR(16),
    signal_strength             INTEGER,
    carrier_name                VARCHAR(64),
    printer_status              INTEGER,
    uptime_ms                   BIGINT,
    boot_count                  BIGINT,
    latitude                    NUMERIC,
    longitude                   NUMERIC,
    location_accuracy_m         REAL,
    location_at                 TIMESTAMPTZ,
    location_permission         BOOLEAN,
    location_services_enabled   BOOLEAN,
    raw_payload                 JSONB,
    collected_at                TIMESTAMPTZ NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL
);

-- ─── products ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.products (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    code        VARCHAR(255) NOT NULL,
    status      VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

-- ─── tids (Terminal ID configurations) ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.tids (
    id                              BIGSERIAL PRIMARY KEY,
    terminal_id                     VARCHAR(8) NOT NULL,
    merchant_id                     VARCHAR(255) NOT NULL,
    merchant_name                   VARCHAR(255) NOT NULL,
    merchant_category_code          VARCHAR(255),
    merchant_account_name           VARCHAR(255),
    merchant_acct_domicile_bank_code VARCHAR(255),
    merchant_acquirer_id            VARCHAR(255),
    merchant_physical_addr          VARCHAR(255),
    merchant_address_lga_code       VARCHAR(255),
    terminal_address                VARCHAR(255),
    terminal_address_lga_code       VARCHAR(255),
    terminal_type                   VARCHAR(255),
    terminal_model_description      VARCHAR(255),
    terminal_owner_code             VARCHAR(255),
    terminal_group_id               VARCHAR(255),
    ptsp_code                       VARCHAR(255),
    state_code                      VARCHAR(255),
    bank_acc_no                     VARCHAR(255) NOT NULL,
    bank_code                       VARCHAR(255),
    agent_code                      VARCHAR(255),
    contact_name                    VARCHAR(255),
    contact_title                   VARCHAR(255),
    email                           VARCHAR(255),
    mobile_phone                    VARCHAR(255),
    bvn                             VARCHAR(255),
    tin                             VARCHAR(255),
    gps_info                        VARCHAR(255),
    app_name                        VARCHAR(255),
    app_version                     VARCHAR(255),
    business_occupation_code        VARCHAR(255),
    internal                        BOOLEAN NOT NULL,
    mastercard_acquirer_id_number   VARCHAR(255),
    visa_acquirer_id_number         VARCHAR(255),
    verve_acquirer_id_number        VARCHAR(255),
    processor                       VARCHAR(255),
    user_id                         BIGINT,
    created_at                      TIMESTAMPTZ,
    updated_at                      TIMESTAMPTZ
);

-- ─── wallets ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.wallets (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    balance             NUMERIC NOT NULL,
    locked_amount       NUMERIC NOT NULL DEFAULT 0,
    type                VARCHAR(20) NOT NULL,
    pnd                 BOOLEAN NOT NULL DEFAULT false,
    pnd_reason          VARCHAR(500),
    balance_checksum    VARCHAR(64),
    created_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ
);

-- ─── statements ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.statements (
    id                      BIGSERIAL PRIMARY KEY,
    wallet_id               BIGINT NOT NULL,
    amount                  NUMERIC NOT NULL,
    previous_balance        NUMERIC NOT NULL,
    current_balance         NUMERIC NOT NULL,
    type                    VARCHAR(10) NOT NULL,
    category                VARCHAR(50) NOT NULL,
    description             VARCHAR(255),
    reversal                BOOLEAN NOT NULL,
    source_id               BIGINT,
    source_type             VARCHAR(255),
    source_reference        VARCHAR(40),
    device_serial           VARCHAR(64),
    device_previous_balance NUMERIC,
    device_current_balance  NUMERIC,
    created_at              TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ
);

-- ─── virtual_accounts ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.virtual_accounts (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT,
    account_number      VARCHAR(20) NOT NULL,
    account_name        VARCHAR(255) NOT NULL,
    bank_code           VARCHAR(10) NOT NULL,
    bank_name           VARCHAR(255) NOT NULL,
    purpose             VARCHAR(32),
    purpose_reference   VARCHAR(64),
    single_use          BOOLEAN NOT NULL DEFAULT false,
    disabled_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ
);

-- ─── addresses ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.addresses (
    id                  BIGSERIAL PRIMARY KEY,
    addressable_id      BIGINT NOT NULL,
    addressable_type    VARCHAR(255) NOT NULL,
    address             VARCHAR(255),
    country_id          BIGINT NOT NULL,
    state_id            BIGINT,
    lga                 VARCHAR(255),
    status_code         VARCHAR(255),
    created_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ
);

-- ─── onboardings ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.onboardings (
    id                          BIGSERIAL PRIMARY KEY,
    email                       VARCHAR(255),
    phone_number                VARCHAR(255),
    first_name                  VARCHAR(255),
    last_name                   VARCHAR(255),
    middle_name                 VARCHAR(255),
    gender                      VARCHAR(255),
    date_of_birth               DATE,
    bvn                         VARCHAR(255),
    bvn_is_validated            BOOLEAN,
    bvn_phone_number            VARCHAR(255),
    is_bvn_phone_number         BOOLEAN,
    email_is_validated          BOOLEAN,
    phone_number_is_validated   BOOLEAN,
    liveliness_is_validated     BOOLEAN,
    selfie_url                  VARCHAR(255),
    reference                   VARCHAR(255),
    address                     VARCHAR(255),
    country_id                  BIGINT,
    state_id                    BIGINT,
    lga                         VARCHAR(255),
    aggregator_id               BIGINT,
    aggregator_code             VARCHAR(255),
    auto_matched                BOOLEAN,
    created_at                  TIMESTAMP,
    updated_at                  TIMESTAMP
);

-- ─── Supporting lookup tables ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.statuses (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    code        VARCHAR(255),
    context     VARCHAR(255),
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.channels (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    code        VARCHAR(255),
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.payment_methods (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    code        VARCHAR(255),
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.tiers (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    code        VARCHAR(255),
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.countries (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    code        VARCHAR(255),
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.states (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    country_id  BIGINT,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.lgas (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    state_id    BIGINT,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.nins (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT,
    nin         VARCHAR(255),
    verified    BOOLEAN DEFAULT false,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.notifications (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT,
    title       VARCHAR(255),
    body        TEXT,
    type        VARCHAR(255),
    read        BOOLEAN DEFAULT false,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.nibss_active_terminals (
    id                  BIGSERIAL PRIMARY KEY,
    terminal_id         VARCHAR(255),
    key_status          VARCHAR(255),
    last_error          TEXT,
    last_used_at        TIMESTAMPTZ,
    keys_downloaded_at  TIMESTAMPTZ,
    created_at          TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.terminal_tid (
    id          BIGSERIAL PRIMARY KEY,
    terminal_id BIGINT,
    tid_id      BIGINT,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.instant_settlements (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT,
    status      VARCHAR(50),
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

-- ─── disputes ────────────────────────────────────────────────────────────────
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

-- ─── conversations (dispute messages) ────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.conversations (
    id                      BIGSERIAL PRIMARY KEY,
    dispute_id              BIGINT NOT NULL,
    user_id                 BIGINT,
    sender_type             VARCHAR(20) NOT NULL,
    sender_name             VARCHAR(255),
    message                 TEXT NOT NULL,
    created_at              TIMESTAMPTZ
);

-- ─── configurations (commission/charge settings) ─────────────────────────────
CREATE TABLE IF NOT EXISTS public.configurations (
    id          BIGSERIAL PRIMARY KEY,
    module      VARCHAR(50) NOT NULL,
    type        VARCHAR(50) NOT NULL,
    name        VARCHAR(50) NOT NULL DEFAULT 'default',
    expression  TEXT,
    value       VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_configurations_module_type ON public.configurations (module, type);
CREATE INDEX IF NOT EXISTS idx_configurations_module_type_name ON public.configurations (module, type, name);


-- ─── audit_logs ─────────────────────────────────────────────────────────────
-- Stores audit trail of all non-GET actions performed by merchant users
CREATE TABLE IF NOT EXISTS public.audit_logs (
    id                          BIGSERIAL PRIMARY KEY,
    merchant_id                 BIGINT NOT NULL,
    user_id                     BIGINT NOT NULL,
    user_name                   VARCHAR(255),
    user_email                  VARCHAR(255),
    user_role                   VARCHAR(50),
    method                      VARCHAR(10) NOT NULL,
    path                        VARCHAR(500) NOT NULL,
    action                      VARCHAR(255),
    request_body                TEXT,
    response_status             INTEGER,
    ip_address                  VARCHAR(64),
    user_agent                  VARCHAR(500),
    created_at                  TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_merchant_id ON public.audit_logs(merchant_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id ON public.audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON public.audit_logs(created_at);
