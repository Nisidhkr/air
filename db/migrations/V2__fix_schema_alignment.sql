-- ============================================================================
-- Fylo — V2: align schema with FYLO_PROJECT_BACKBONE.md §5.2
-- (verification failures [2.4] indexes, [2.5] foreign keys, [2.6] storage cols)
--
-- The backbone's index definitions reference backbone column names, so this
-- migration renames the V1 columns to match, adds the columns the backbone
-- requires that V1 modeled differently (refresh_token, device_token,
-- session_code, is_active, is_read, is_guest, storage_used/limit), then
-- recreates every index under its backbone name.
-- ============================================================================

-- The V1 usage view references columns renamed below; replaced by real
-- storage_used/storage_limit columns (backbone §5.2).
DROP VIEW IF EXISTS user_storage_usage;

-- ── Column renames to backbone names ───────────────────────────────────────
ALTER TABLE devices           RENAME COLUMN owner_user_id     TO user_id;
ALTER TABLE share_links       RENAME COLUMN slug              TO short_code;
ALTER TABLE share_links       RENAME COLUMN owner_user_id     TO owner_id;
ALTER TABLE transfer_sessions RENAME COLUMN initiator_user_id TO sender_id;
ALTER TABLE transfer_sessions RENAME COLUMN peer_user_id      TO receiver_id;
ALTER TABLE transfer_requests RENAME COLUMN from_user_id      TO sender_id;
ALTER TABLE transfer_requests RENAME COLUMN to_user_id        TO receiver_id;
ALTER TABLE transfer_requests RENAME COLUMN state             TO status;

-- ── users: guest flag + storage accounting (backbone §5.2) ─────────────────
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_guest      BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS storage_used  BIGINT  NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS storage_limit BIGINT  NOT NULL DEFAULT 10737418240; -- 10 GB

-- ── sessions: opaque refresh token storage (backbone keeps the token) ──────
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS refresh_token VARCHAR(512) NOT NULL DEFAULT '';
UPDATE sessions SET refresh_token = id::text WHERE refresh_token = '';
ALTER TABLE sessions ALTER COLUMN refresh_token DROP DEFAULT;

-- ── devices: long-lived device token (backbone §5.2) ───────────────────────
ALTER TABLE devices ADD COLUMN IF NOT EXISTS device_token VARCHAR(512) NOT NULL DEFAULT '';
UPDATE devices SET device_token = id::text WHERE device_token = '';
ALTER TABLE devices ALTER COLUMN device_token DROP DEFAULT;

-- ── share_links: active flag (backbone models revocation as is_active) ─────
-- NOTE: writers must keep is_active in sync with revoked_at
-- (is_active = revoked_at IS NULL).
ALTER TABLE share_links ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;
UPDATE share_links SET is_active = (revoked_at IS NULL);

-- ── transfer_sessions: direct-share session code + link FK (backbone §5.2) ─
ALTER TABLE transfer_sessions ADD COLUMN IF NOT EXISTS session_code VARCHAR(12);
ALTER TABLE transfer_sessions ADD COLUMN IF NOT EXISTS
    share_link_id UUID REFERENCES share_links (id) ON DELETE SET NULL;

-- Backbone status vocabulary includes PENDING/CONNECTING/IN_PROGRESS; keep
-- the V1 engine states too so existing rows stay valid.
ALTER TABLE transfer_sessions DROP CONSTRAINT IF EXISTS transfer_sessions_status_check;
ALTER TABLE transfer_sessions ADD CONSTRAINT transfer_sessions_status_check
    CHECK (status IN ('PENDING','CONNECTING','IN_PROGRESS',
                      'QUEUED','ACTIVE','PAUSED','COMPLETED',
                      'FAILED','CANCELLED','AWAITING_APPROVAL','REJECTED'));

-- ── Foreign key alignment (failure [2.5]) ──────────────────────────────────
-- 2a. devices.user_id → CASCADE (guests are users rows with is_guest = TRUE)
ALTER TABLE devices DROP CONSTRAINT IF EXISTS devices_user_id_fkey;
ALTER TABLE devices DROP CONSTRAINT IF EXISTS devices_owner_user_id_fkey;
ALTER TABLE devices ADD CONSTRAINT devices_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

-- 2b. share_links.file_id → CASCADE
ALTER TABLE share_links DROP CONSTRAINT IF EXISTS share_links_file_id_fkey;
ALTER TABLE share_links ADD CONSTRAINT share_links_file_id_fkey
    FOREIGN KEY (file_id) REFERENCES files (id) ON DELETE CASCADE;

-- 2d. transfer_requests.transfer_session_id → CASCADE
ALTER TABLE transfer_requests
    DROP CONSTRAINT IF EXISTS transfer_requests_transfer_session_id_fkey;
ALTER TABLE transfer_requests ADD CONSTRAINT
    transfer_requests_transfer_session_id_fkey
    FOREIGN KEY (transfer_session_id) REFERENCES transfer_sessions (id)
    ON DELETE CASCADE;

-- ── Index alignment (failure [2.4]): drop V1 names, create backbone names ──
DROP INDEX IF EXISTS users_username_live_uq;
DROP INDEX IF EXISTS users_email_live_uq;
DROP INDEX IF EXISTS sessions_user_live_ix;
DROP INDEX IF EXISTS devices_owner_live_ix;
DROP INDEX IF EXISTS share_links_slug_live_uq;
DROP INDEX IF EXISTS share_links_owner_live_ix;
DROP INDEX IF EXISTS share_links_expiry_ix;
DROP INDEX IF EXISTS ts_initiator_live_ix;
DROP INDEX IF EXISTS ts_status_ix;
DROP INDEX IF EXISTS tr_inbox_ix;
DROP INDEX IF EXISTS th_user_time_ix;
DROP INDEX IF EXISTS notif_user_time_ix;

CREATE UNIQUE INDEX idx_users_username
    ON users(username)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX idx_users_email
    ON users(email)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_sessions_user_id
    ON sessions(user_id);

CREATE UNIQUE INDEX idx_sessions_refresh_token
    ON sessions(refresh_token);

CREATE INDEX idx_devices_user_id
    ON devices(user_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_devices_token
    ON devices(device_token);

CREATE UNIQUE INDEX idx_share_links_short_code
    ON share_links(short_code)
    WHERE is_active = TRUE;

CREATE INDEX idx_share_links_owner_id
    ON share_links(owner_id);

CREATE INDEX idx_share_links_expires_at
    ON share_links(expires_at)
    WHERE expires_at IS NOT NULL;

CREATE INDEX idx_ts_sender_id
    ON transfer_sessions(sender_id);

CREATE INDEX idx_ts_receiver_id
    ON transfer_sessions(receiver_id);

CREATE INDEX idx_ts_session_code
    ON transfer_sessions(session_code)
    WHERE status IN ('PENDING','CONNECTING');

CREATE INDEX idx_ts_status
    ON transfer_sessions(status);

CREATE INDEX idx_tr_receiver_id
    ON transfer_requests(receiver_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_th_user_id
    ON transfer_history(user_id, created_at DESC);

-- ── notifications: read flag column (backbone models read state as boolean) ─
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS is_read BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE notifications SET is_read = (read_at IS NOT NULL);

CREATE INDEX idx_notifications_user_id
    ON notifications(user_id, is_read, created_at DESC);
