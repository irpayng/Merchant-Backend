-- ============================================================================
-- Performance optimization indexes for slow queries
-- See docs/slow-query-optimizations.md for details on the queries being optimized
-- ============================================================================

-- Enable pg_trgm extension for efficient LIKE '%term%' searches
-- This must be done by a superuser if not already enabled
-- CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- =============================================================================
-- Terminal Metrics Index
-- =============================================================================
-- The loose-index-scan CTE for findLatestPerSerial() relies on this composite
-- index to walk serials in order and fetch the newest row per serial efficiently.
-- Without it, the query falls back to a full seq scan + sort.
CREATE INDEX IF NOT EXISTS idx_terminal_metrics_serial_created 
    ON terminal_metrics (serial, created_at DESC);

-- =============================================================================
-- Audit Logs Indexes for LIKE searches
-- =============================================================================
-- The audit log search queries use LIKE '%term%' on multiple columns.
-- B-tree indexes don't help with leading wildcards, so we use GIN trigram indexes.
-- Note: requires pg_trgm extension
-- 
-- If pg_trgm is not available, these indexes won't be created but the app
-- will still work (just slower). Uncomment after enabling the extension.

-- CREATE INDEX IF NOT EXISTS idx_audit_logs_user_name_trgm 
--     ON audit_logs USING gin (user_name gin_trgm_ops);
-- CREATE INDEX IF NOT EXISTS idx_audit_logs_user_email_trgm 
--     ON audit_logs USING gin (user_email gin_trgm_ops);
-- CREATE INDEX IF NOT EXISTS idx_audit_logs_action_trgm 
--     ON audit_logs USING gin (action gin_trgm_ops);

-- Standard B-tree indexes for exact/prefix matches and ORDER BY
CREATE INDEX IF NOT EXISTS idx_audit_logs_merchant_created 
    ON audit_logs (merchant_id, created_at DESC);

-- =============================================================================
-- Transaction Indexes for Dashboard/Analytics Queries
-- =============================================================================
-- The dashboard runs many queries grouping by status_code within a date range.
-- This composite index supports those patterns efficiently.
CREATE INDEX IF NOT EXISTS idx_transactions_created_status 
    ON transactions (created_at, status_code);

-- Index for terminal-based transaction lookups (used in dashboard terminalStatus)
CREATE INDEX IF NOT EXISTS idx_transactions_terminal_created 
    ON transactions (terminal_id, created_at DESC) 
    WHERE terminal_id IS NOT NULL;

-- Index for user-scoped transaction queries
CREATE INDEX IF NOT EXISTS idx_transactions_user_created 
    ON transactions (user_id, created_at DESC);

-- =============================================================================
-- Device Activity Indexes
-- =============================================================================
-- The device activity filter queries search by merchant + reference (serial)
CREATE INDEX IF NOT EXISTS idx_activities_user_reference 
    ON activities (user_id, reference) 
    WHERE reference IS NOT NULL AND reference != '';

CREATE INDEX IF NOT EXISTS idx_activities_user_created 
    ON activities (user_id, created_at DESC);

-- =============================================================================
-- Dispute Indexes for LIKE searches
-- =============================================================================
CREATE INDEX IF NOT EXISTS idx_disputes_user_created 
    ON disputes (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_disputes_user_status 
    ON disputes (user_id, status_code);
