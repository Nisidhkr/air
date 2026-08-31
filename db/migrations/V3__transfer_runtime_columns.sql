-- ============================================================================
-- Fylo — V3: runtime columns for persisted transfer sessions (backbone §6, §11)
-- The Java TransferSessionRecord persists the per-session transfer token and
-- resume cursor; V1/V2 did not carry them.
-- ============================================================================

ALTER TABLE transfer_sessions ADD COLUMN IF NOT EXISTS transfer_token   VARCHAR(64);
ALTER TABLE transfer_sessions ADD COLUMN IF NOT EXISTS last_chunk_index INTEGER;

-- Rendezvous rows are created before a direction is known.
ALTER TABLE transfer_sessions ALTER COLUMN direction SET DEFAULT 'SEND';
