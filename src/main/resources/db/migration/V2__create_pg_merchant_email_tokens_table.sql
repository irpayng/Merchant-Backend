-- Email verification tokens for Payment Gateway merchants.
-- PG merchants use a separate dashboard and don't have MerchantUser records,
-- so their email verification tokens are stored in a dedicated table.

CREATE TABLE IF NOT EXISTS merchant.pg_merchant_email_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    email VARCHAR(255) NOT NULL,
    otp VARCHAR(6) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    verified_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pg_email_tokens_email ON merchant.pg_merchant_email_tokens(email);
CREATE INDEX IF NOT EXISTS idx_pg_email_tokens_user_id ON merchant.pg_merchant_email_tokens(user_id);
