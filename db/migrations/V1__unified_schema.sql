-- ============================================================================
-- Fylo — Unified Platform schema (PostgreSQL 15+)
-- V1: the ONE database behind every mode.
--
-- Conventions applied to EVERY table:
--   * UUID primary keys (application-generated, matches the Java records)
--   * Audit fields: created_at, updated_at (trigger-maintained), deleted_at
--   * Soft delete: deleted_at IS NULL means live; partial indexes exclude
--     soft-deleted rows so hot paths never pay for tombstones
--   * Foreign keys always indexed
-- Flyway/Liquibase compatible: file name is a Flyway V1 migration.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto; -- gen_random_uuid() for defaults

-- ── updated_at trigger, shared by all tables ───────────────────────────────
CREATE OR REPLACE FUNCTION touch_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- users — accounts (modes 3 & 4). Mirrors p2p.user.User.
-- ============================================================================
CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username       VARCHAR(20)  NOT NULL,
    display_name   VARCHAR(80)  NOT NULL,
    email          VARCHAR(254),                       -- optional, for recovery
    password_hash  VARCHAR(200) NOT NULL,              -- pbkdf2$iter$salt$hash
    plan_tier      VARCHAR(20)  NOT NULL DEFAULT 'FREE'
                   CHECK (plan_tier IN ('FREE','PREMIUM')),
    plan_expires_at TIMESTAMPTZ,                       -- NULL = not premium / lifetime
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ,
    CONSTRAINT users_username_format CHECK (username ~ '^[a-z0-9_]{3,20}$')
);
-- One live account per username; freed on soft delete.
CREATE UNIQUE INDEX users_username_live_uq ON users (username) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX users_email_live_uq    ON users (lower(email))
    WHERE deleted_at IS NULL AND email IS NOT NULL;
CREATE INDEX users_username_prefix_ix ON users (username text_pattern_ops)
    WHERE deleted_at IS NULL;                          -- @prefix search
CREATE TRIGGER users_touch BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ============================================================================
-- sessions — refresh-token rotation registry. Mirrors p2p.auth.SessionService.
-- One row per outstanding refresh token (jti); consumed exactly once.
-- ============================================================================
CREATE TABLE sessions (
    id             UUID PRIMARY KEY,                    -- = refresh JWT jti
    user_id        UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id      UUID,                                -- optional binding
    replaced_by    UUID REFERENCES sessions (id),       -- rotation chain
    ip_address     INET,
    user_agent     VARCHAR(300),
    expires_at     TIMESTAMPTZ NOT NULL,
    consumed_at    TIMESTAMPTZ,                         -- non-NULL = used/rotated
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ                          -- revocation
);
CREATE INDEX sessions_user_live_ix ON sessions (user_id)
    WHERE deleted_at IS NULL AND consumed_at IS NULL;
CREATE INDEX sessions_expiry_ix ON sessions (expires_at); -- sweeper
CREATE TRIGGER sessions_touch BEFORE UPDATE ON sessions
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ============================================================================
-- devices — every device identity the platform has seen.
-- Mirrors p2p.device.DeviceRegistry entries (per-owner rows once logged in).
-- ============================================================================
CREATE TABLE devices (
    id             UUID PRIMARY KEY,                    -- stable DeviceIdentity id
    owner_user_id  UUID REFERENCES users (id) ON DELETE SET NULL, -- NULL = guest device
    name           VARCHAR(120) NOT NULL,
    os             VARCHAR(60),
    device_type    VARCHAR(30),                         -- phone|tablet|laptop|...
    last_host      VARCHAR(120),
    last_api_port  INTEGER CHECK (last_api_port BETWEEN 0 AND 65535),
    capabilities   JSONB NOT NULL DEFAULT '{}'::jsonb,  -- clipboard, sync, ...
    last_seen_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ
);
CREATE INDEX devices_owner_live_ix ON devices (owner_user_id) WHERE deleted_at IS NULL;
CREATE INDEX devices_last_seen_ix  ON devices (last_seen_at DESC) WHERE deleted_at IS NULL;
CREATE TRIGGER devices_touch BEFORE UPDATE ON devices
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ============================================================================
-- trusted_devices — directed trust edge: truster accepts offers from trustee
-- without approval. Mirrors DeviceRegistry.trusted + QrPairingService result.
-- ============================================================================
CREATE TABLE trusted_devices (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    truster_device_id UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    trustee_device_id UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    paired_via        VARCHAR(20) NOT NULL DEFAULT 'QR'
                      CHECK (paired_via IN ('QR','CODE','MANUAL')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,                      -- trust revoked
    CONSTRAINT trusted_no_self CHECK (truster_device_id <> trustee_device_id)
);
CREATE UNIQUE INDEX trusted_pair_live_uq
    ON trusted_devices (truster_device_id, trustee_device_id)
    WHERE deleted_at IS NULL;
CREATE INDEX trusted_trustee_ix ON trusted_devices (trustee_device_id)
    WHERE deleted_at IS NULL;
CREATE TRIGGER trusted_devices_touch BEFORE UPDATE ON trusted_devices
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ============================================================================
-- files — objects in the ONE storage layer. Mirrors p2p.storage.StoredObject.
-- storage_provider + storage_key locate the bytes; rows survive provider moves.
-- ============================================================================
CREATE TABLE files (
    id               UUID PRIMARY KEY,                  -- = StoredObject.objectId
    owner_user_id    UUID REFERENCES users (id) ON DELETE SET NULL,
    file_name        VARCHAR(255) NOT NULL,             -- sanitized display name
    size_bytes       BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256_hex       CHAR(64) NOT NULL,
    content_type     VARCHAR(120),
    storage_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL'
                     CHECK (storage_provider IN ('LOCAL','MINIO','S3')),
    storage_key      VARCHAR(512) NOT NULL,             -- path / object key
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ                        -- swept asynchronously
);
CREATE INDEX files_owner_live_ix ON files (owner_user_id) WHERE deleted_at IS NULL;
CREATE INDEX files_sha_ix        ON files (sha256_hex);  -- dedup lookups
CREATE TRIGGER files_touch BEFORE UPDATE ON files
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ============================================================================
-- share_links — mode 4. Mirrors p2p.share.ShareLink (+ premium fields).
-- ============================================================================
CREATE TABLE share_links (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug           VARCHAR(16)  NOT NULL,
    file_id        UUID NOT NULL REFERENCES files (id) ON DELETE RESTRICT,
    owner_user_id  UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    password_hash  VARCHAR(200),                        -- premium: protected links
    max_downloads  BIGINT NOT NULL DEFAULT 0 CHECK (max_downloads >= 0), -- 0 = ∞
    download_count BIGINT NOT NULL DEFAULT 0 CHECK (download_count >= 0),
    expires_at     TIMESTAMPTZ NOT NULL,                -- free default: now()+2d
    revoked_at     TIMESTAMPTZ,                         -- premium: revocation
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ
);
CREATE UNIQUE INDEX share_links_slug_live_uq ON share_links (slug)
    WHERE deleted_at IS NULL;
CREATE INDEX share_links_owner_live_ix ON share_links (owner_user_id)
    WHERE deleted_at IS NULL;
CREATE INDEX share_links_expiry_ix ON share_links (expires_at)
    WHERE deleted_at IS NULL AND revoked_at IS NULL;    -- expiry sweeper
CREATE INDEX share_links_file_ix ON share_links (file_id);
CREATE TRIGGER share_links_touch BEFORE UPDATE ON share_links
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ============================================================================
-- transfer_sessions — one row per engine transfer (all four modes).
-- Mirrors TransferManager.Transfer + TransferManifest identifiers.
-- ============================================================================
CREATE TABLE transfer_sessions (
    id                UUID PRIMARY KEY,                 -- = engine transfer id
    mode              VARCHAR(10) NOT NULL
                      CHECK (mode IN ('DIRECT','NEARBY','USERNAME','LINK')),
    direction         VARCHAR(8)  NOT NULL CHECK (direction IN ('SEND','RECEIVE')),
    status            VARCHAR(20) NOT NULL
                      CHECK (status IN ('QUEUED','ACTIVE','PAUSED','COMPLETED',
                                        'FAILED','CANCELLED','AWAITING_APPROVAL','REJECTED')),
    file_name         VARCHAR(255) NOT NULL,
    size_bytes        BIGINT NOT NULL CHECK (size_bytes >= 0),
    transferred_bytes BIGINT NOT NULL DEFAULT 0 CHECK (transferred_bytes >= 0),
    sha256_hex        CHAR(64),
    initiator_user_id UUID REFERENCES users (id)   ON DELETE SET NULL, -- NULL = guest
    peer_user_id      UUID REFERENCES users (id)   ON DELETE SET NULL,
    sender_device_id  UUID REFERENCES devices (id) ON DELETE SET NULL,
    receiver_device_id UUID REFERENCES devices (id) ON DELETE SET NULL,
    file_id           UUID REFERENCES files (id)   ON DELETE SET NULL, -- LINK mode
    error             VARCHAR(500),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT transferred_within_size CHECK (transferred_bytes <= size_bytes)
);
CREATE INDEX ts_initiator_live_ix ON transfer_sessions (initiator_user_id, created_at DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX ts_status_ix ON transfer_sessions (status)
    WHERE deleted_at IS NULL AND status IN ('QUEUED','ACTIVE','PAUSED');
CREATE INDEX ts_device_ix ON transfer_sessions (sender_device_id, receiver_device_id);
CREATE TRIGGER transfer_sessions_touch BEFORE UPDATE ON transfer_sessions
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ============================================================================
-- transfer_requests — mode 3 handshake. Mirrors p2p.share.TransferRequest.
-- Connection secrets live here until accept, then are returned once.
-- ============================================================================
CREATE TABLE transfer_requests (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_user_id   UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    to_user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    file_name      VARCHAR(255) NOT NULL,
    size_bytes     BIGINT NOT NULL CHECK (size_bytes >= 0),
    share_port     INTEGER NOT NULL CHECK (share_port BETWEEN 1 AND 65535),
    share_token    VARCHAR(64) NOT NULL,                -- revealed only on accept
    state          VARCHAR(12) NOT NULL DEFAULT 'PENDING'
                   CHECK (state IN ('PENDING','ACCEPTED','REJECTED','EXPIRED','COMPLETED')),
    expires_at     TIMESTAMPTZ NOT NULL,
    responded_at   TIMESTAMPTZ,
    transfer_session_id UUID REFERENCES transfer_sessions (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ,
    CONSTRAINT no_self_request CHECK (from_user_id <> to_user_id)
);
CREATE INDEX tr_inbox_ix ON transfer_requests (to_user_id, created_at DESC)
    WHERE deleted_at IS NULL AND state = 'PENDING';
CREATE INDEX tr_outbox_ix ON transfer_requests (from_user_id, created_at DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX tr_expiry_ix ON transfer_requests (expires_at) WHERE state = 'PENDING';
CREATE TRIGGER transfer_requests_touch BEFORE UPDATE ON transfer_requests
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ============================================================================
-- transfer_history — the durable per-account ledger (append-mostly).
-- Mirrors p2p.user.TransferHistoryService.Entry. Separate from
-- transfer_sessions so history retention (plan-based) can differ from
-- operational session retention.
-- ============================================================================
CREATE TABLE transfer_history (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    direction      VARCHAR(8)  NOT NULL CHECK (direction IN ('SEND','RECEIVE')),
    mode           VARCHAR(10) NOT NULL
                   CHECK (mode IN ('DIRECT','NEARBY','USERNAME','LINK')),
    file_name      VARCHAR(255) NOT NULL,
    size_bytes     BIGINT NOT NULL CHECK (size_bytes >= 0),
    counterparty   VARCHAR(140),                        -- '@aman' | 'link:slug' | device name
    status         VARCHAR(20) NOT NULL,
    transfer_session_id UUID REFERENCES transfer_sessions (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ                          -- plan-based pruning
);
CREATE INDEX th_user_time_ix ON transfer_history (user_id, created_at DESC)
    WHERE deleted_at IS NULL;
CREATE TRIGGER transfer_history_touch BEFORE UPDATE ON transfer_history
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ============================================================================
-- notifications — per-user queue. Mirrors p2p.user.NotificationService.
-- delivered_at/read_at support both poll-drain (Part 1) and WS push (Part 2).
-- ============================================================================
CREATE TABLE notifications (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type           VARCHAR(40) NOT NULL,                -- transfer_request, ...
    data           JSONB NOT NULL DEFAULT '{}'::jsonb,
    delivered_at   TIMESTAMPTZ,
    read_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ
);
CREATE INDEX notif_undelivered_ix ON notifications (user_id, created_at)
    WHERE deleted_at IS NULL AND delivered_at IS NULL;
CREATE INDEX notif_user_time_ix ON notifications (user_id, created_at DESC)
    WHERE deleted_at IS NULL;
CREATE TRIGGER notifications_touch BEFORE UPDATE ON notifications
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ============================================================================
-- Convenience view: live storage usage per user (plan quota enforcement).
-- ============================================================================
CREATE VIEW user_storage_usage AS
SELECT u.id AS user_id,
       COALESCE(SUM(f.size_bytes), 0) AS bytes_used,
       COUNT(sl.id) FILTER (WHERE sl.revoked_at IS NULL
                              AND sl.expires_at > now()) AS active_links
FROM users u
LEFT JOIN files f        ON f.owner_user_id = u.id AND f.deleted_at IS NULL
LEFT JOIN share_links sl ON sl.owner_user_id = u.id AND sl.deleted_at IS NULL
WHERE u.deleted_at IS NULL
GROUP BY u.id;
