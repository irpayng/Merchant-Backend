-- =============================================================================
-- V1: Create branch_tids join table for many-to-many TID-branch assignment
-- One TID can be assigned to multiple branches simultaneously.
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.branch_tids (
    id          BIGSERIAL PRIMARY KEY,
    branch_id   BIGINT NOT NULL,
    tid_id      BIGINT NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(branch_id, tid_id)
);

CREATE INDEX IF NOT EXISTS idx_branch_tids_branch_id ON public.branch_tids(branch_id);
CREATE INDEX IF NOT EXISTS idx_branch_tids_tid_id ON public.branch_tids(tid_id);

COMMENT ON TABLE public.branch_tids IS 'Many-to-many join table for TID-branch assignments. A TID can be assigned to multiple branches.';
