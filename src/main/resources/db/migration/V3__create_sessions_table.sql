-- Create sessions table for JWT revocation capability
-- Sessions are created on login and checked on each request
-- Revoked sessions cause authentication to fail even if the JWT is still valid

CREATE TABLE IF NOT EXISTS merchant.sessions (
    id BIGSERIAL PRIMARY KEY,
    merchant_user_id BIGINT NOT NULL REFERENCES merchant.merchant_users(id) ON DELETE CASCADE,
    tms_user_id BIGINT NOT NULL,
    user_type VARCHAR(20) DEFAULT 'user',
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    channel VARCHAR(20) DEFAULT 'dashboard',
    device_info VARCHAR(500),
    ip_address VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    revocation_reason VARCHAR(50)
);

-- Index for token lookup on each request (most frequent query)
CREATE INDEX IF NOT EXISTS idx_sessions_token_hash ON merchant.sessions (token_hash);

-- Index for revoking all sessions for a tms-user (block user, password change)
CREATE INDEX IF NOT EXISTS idx_sessions_tms_user_id ON merchant.sessions (tms_user_id);

-- Index for revoking sessions by merchant_user_id
CREATE INDEX IF NOT EXISTS idx_sessions_merchant_user_id ON merchant.sessions (merchant_user_id);

-- Index for cleanup job (delete expired sessions)
CREATE INDEX IF NOT EXISTS idx_sessions_expires_at ON merchant.sessions (expires_at);

COMMENT ON TABLE merchant.sessions IS 'Tracks active dashboard sessions for revocation capability';
COMMENT ON COLUMN merchant.sessions.tms_user_id IS 'users.id for owners, operators.id for staff';
COMMENT ON COLUMN merchant.sessions.user_type IS 'user for owners, operator for staff';
COMMENT ON COLUMN merchant.sessions.token_hash IS 'SHA256 hash of the JWT (not the token itself)';
COMMENT ON COLUMN merchant.sessions.revocation_reason IS 'blocked, password_changed, device_reset, logout';
