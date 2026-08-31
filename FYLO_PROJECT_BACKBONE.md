# FYLO — Unified File Transfer Platform
## Project Backbone & Backend Architecture

> **Version:** 1.0.0  
> **Stack:** Java 25 · PostgreSQL · MinIO/S3 · WebSocket · REST  
> **Architecture:** Monorepo · Single Unified Backend · Single Transfer Engine

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Core Principles](#2-core-principles)
3. [System Architecture](#3-system-architecture)
4. [Java Package Structure](#4-java-package-structure)
5. [Database Schema](#5-database-schema)
6. [Transfer Engine Design](#6-transfer-engine-design)
7. [Device Discovery Design](#7-device-discovery-design)
8. [Authentication & User System](#8-authentication--user-system)
9. [Storage System](#9-storage-system)
10. [API Design — Modules & Endpoints](#10-api-design--modules--endpoints)
11. [Transfer Modes — Detailed Flows](#11-transfer-modes--detailed-flows)
12. [Security Architecture](#12-security-architecture)
13. [Plans & Entitlements](#13-plans--entitlements)
14. [Performance Targets & Scaling](#14-performance-targets--scaling)
15. [Docker & Infrastructure Setup](#15-docker--infrastructure-setup)
16. [CI/CD Pipeline](#16-cicd-pipeline)
17. [Monitoring & Observability](#17-monitoring--observability)
18. [Testing Strategy](#18-testing-strategy)
19. [Migration Plan](#19-migration-plan)
20. [Deliverable Checklist](#20-deliverable-checklist)

---

## 1. Project Overview

**Fylo** is a cross-platform file transfer application supporting four distinct sharing modes, all powered by one unified backend server and one shared transfer engine.

| Mode | Name | Login Required | Storage | Max File |
|------|------|---------------|---------|----------|
| 1 | Direct Share | ❌ No | None (live) | Unlimited |
| 2 | Nearby Share | ❌ No (basic) | None (live) | Unlimited |
| 3 | Username Share | ✅ Yes | Temporary | Plan-based |
| 4 | Link Share | ✅ Yes | Cloud | Plan-based |

**Clients:**
- Web (Browser)
- Desktop (Windows, macOS, Linux)
- Mobile (Android, iOS)

---

## 2. Core Principles

```
┌─────────────────────────────────────────────────────────┐
│  ONE Backend.  ONE Transfer Engine.  FOUR Modes.        │
│                                                         │
│  ✓ No duplicated transfer logic                         │
│  ✓ No separate services per mode                        │
│  ✓ Shared authentication, storage, notifications        │
│  ✓ Storage-provider-agnostic (Local / MinIO / S3)       │
│  ✓ 40 GB transfer on < 100 MB RAM                       │
│  ✓ Zero-copy streaming via FileChannel.transferTo()     │
│  ✓ Virtual Threads (Java 25) for massive concurrency    │
└─────────────────────────────────────────────────────────┘
```

---

## 3. System Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                            CLIENTS                                   │
│         Web  │  Desktop (Win/Mac/Linux)  │  Mobile (Android/iOS)    │
└──────────────┬───────────────────────────────────────────────────────┘
               │  HTTPS / WSS / HTTP/2
               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                         LOAD BALANCER                                │
│                    (Nginx / Traefik / AWS ALB)                       │
└──────────────┬───────────────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    FYLO UNIFIED BACKEND SERVER                       │
│                         (Java 25 · Spring Boot 3.x)                  │
│                                                                      │
│   ┌──────────────────────────────────────────────────────────┐       │
│   │                  API GATEWAY LAYER                       │       │
│   │  Auth Filter │ Rate Limiter │ Request Router │ CORS      │       │
│   └──────────────────────────────────────────────────────────┘       │
│                                                                      │
│   ┌──────────────────────────────────────────────────────────┐       │
│   │               DOMAIN SERVICE LAYER                       │       │
│   │  AuthService │ UserService │ DeviceService │ LinkService │       │
│   │  NotificationService │ HistoryService │ PlanService      │       │
│   └──────────────────────────────────────────────────────────┘       │
│                                                                      │
│   ┌──────────────────────────────────────────────────────────┐       │
│   │           UNIFIED TRANSFER ENGINE (Core)                 │       │
│   │  ChunkedStreamer │ TransferSession │ TransferQueue       │       │
│   │  ResumeManager │ IntegrityVerifier │ ProgressTracker     │       │
│   └──────────────────────────────────────────────────────────┘       │
│                                                                      │
│   ┌──────────────────────────────────────────────────────────┐       │
│   │               DISCOVERY ENGINE                           │       │
│   │  mDNS │ DNS-SD │ QR Pairing │ TrustedDevice Manager      │       │
│   └──────────────────────────────────────────────────────────┘       │
│                                                                      │
│   ┌──────────────────────────────────────────────────────────┐       │
│   │               STORAGE ABSTRACTION                        │       │
│   │  StorageProvider → Local │ MinIO │ S3                    │       │
│   └──────────────────────────────────────────────────────────┘       │
└──────────────┬───────────────────────────────────────────────────────┘
               │
     ┌─────────┴──────────┐
     ▼                    ▼
┌──────────┐      ┌──────────────┐
│PostgreSQL│      │  MinIO / S3  │
│(Primary) │      │  Object Store│
│+ Replica │      │              │
└──────────┘      └──────────────┘
     │
     ▼
┌──────────┐
│  Redis   │
│(Sessions │
│ Cache,   │
│ Pub/Sub) │
└──────────┘
```

---

## 4. Java Package Structure

```
com.fylo/
│
├── FyloApplication.java                    # Main entry point
│
├── config/
│   ├── SecurityConfig.java
│   ├── WebSocketConfig.java
│   ├── DatabaseConfig.java
│   ├── StorageConfig.java
│   ├── RedisConfig.java
│   ├── ThreadConfig.java                   # Virtual Thread executor config
│   └── OpenApiConfig.java
│
├── common/
│   ├── model/
│   │   ├── ApiResponse.java
│   │   ├── ErrorResponse.java
│   │   ├── PagedResponse.java
│   │   └── TransferProgress.java
│   ├── exception/
│   │   ├── FyloException.java
│   │   ├── TransferException.java
│   │   ├── StorageException.java
│   │   ├── AuthException.java
│   │   └── GlobalExceptionHandler.java
│   ├── util/
│   │   ├── ChecksumUtil.java
│   │   ├── TokenUtil.java
│   │   ├── QRUtil.java
│   │   └── FileTypeValidator.java
│   └── constants/
│       ├── TransferStatus.java             # Enum
│       ├── TransferMode.java               # Enum: DIRECT, NEARBY, USERNAME, LINK
│       └── PlanType.java                   # Enum: FREE, PREMIUM
│
├── auth/
│   ├── controller/AuthController.java
│   ├── service/
│   │   ├── AuthService.java
│   │   ├── JwtService.java
│   │   └── SessionService.java
│   ├── model/
│   │   ├── LoginRequest.java
│   │   ├── RegisterRequest.java
│   │   ├── TokenPair.java
│   │   └── GuestTokenRequest.java
│   ├── filter/
│   │   ├── JwtAuthFilter.java
│   │   └── GuestAuthFilter.java
│   └── repository/SessionRepository.java
│
├── user/
│   ├── controller/UserController.java
│   ├── service/UserService.java
│   ├── model/
│   │   ├── User.java                       # Entity
│   │   ├── UserProfile.java
│   │   └── UserSearchResult.java
│   └── repository/UserRepository.java
│
├── device/
│   ├── controller/DeviceController.java
│   ├── service/
│   │   ├── DeviceService.java
│   │   ├── TrustedDeviceService.java
│   │   └── PresenceManager.java
│   ├── model/
│   │   ├── Device.java                     # Entity
│   │   ├── TrustedDevice.java              # Entity
│   │   └── DeviceHeartbeat.java
│   └── repository/
│       ├── DeviceRepository.java
│       └── TrustedDeviceRepository.java
│
├── discovery/
│   ├── controller/DiscoveryController.java
│   ├── service/
│   │   ├── DeviceDiscoveryService.java
│   │   ├── DeviceRegistry.java
│   │   ├── MdnsService.java
│   │   ├── QrPairingService.java
│   │   ├── CodePairingService.java
│   │   └── HeartbeatManager.java
│   └── model/
│       ├── DiscoveredDevice.java
│       ├── PairingCode.java
│       └── QrPayload.java
│
├── transfer/
│   ├── controller/
│   │   ├── TransferController.java
│   │   ├── UploadController.java
│   │   └── DownloadController.java
│   ├── engine/                             # ★ Core Transfer Engine
│   │   ├── TransferEngine.java             # Main orchestrator
│   │   ├── ChunkedStreamer.java            # NIO zero-copy streaming
│   │   ├── TransferSession.java            # Per-transfer state
│   │   ├── TransferQueue.java              # Queue manager
│   │   ├── ResumeManager.java              # Pause/resume logic
│   │   ├── IntegrityVerifier.java          # Checksum per chunk + whole
│   │   ├── ProgressTracker.java            # Real-time progress
│   │   └── RetryPolicy.java
│   ├── service/
│   │   ├── DirectTransferService.java      # Mode 1
│   │   ├── NearbyTransferService.java      # Mode 2
│   │   ├── UsernameTransferService.java    # Mode 3
│   │   └── LinkTransferService.java        # Mode 4
│   ├── model/
│   │   ├── TransferSession.java            # Entity
│   │   ├── TransferRequest.java            # Entity
│   │   ├── TransferHistory.java            # Entity
│   │   ├── FileMetadata.java               # Entity
│   │   ├── ChunkInfo.java
│   │   └── TransferToken.java
│   └── repository/
│       ├── TransferSessionRepository.java
│       ├── TransferRequestRepository.java
│       ├── TransferHistoryRepository.java
│       └── FileMetadataRepository.java
│
├── link/
│   ├── controller/LinkController.java
│   ├── service/LinkService.java
│   ├── model/
│   │   ├── ShareLink.java                  # Entity
│   │   ├── CreateLinkRequest.java
│   │   └── LinkAnalytics.java
│   └── repository/ShareLinkRepository.java
│
├── notification/
│   ├── controller/NotificationController.java
│   ├── service/
│   │   ├── NotificationService.java
│   │   └── WebSocketNotifier.java
│   ├── model/
│   │   ├── Notification.java               # Entity
│   │   └── NotificationPayload.java
│   └── repository/NotificationRepository.java
│
├── storage/
│   ├── StorageProvider.java                # Interface
│   ├── LocalStorageProvider.java
│   ├── MinioStorageProvider.java
│   └── S3StorageProvider.java
│
└── plan/
    ├── service/PlanService.java
    ├── model/
    │   ├── Plan.java                       # Entity
    │   └── Entitlement.java
    └── repository/PlanRepository.java
```

---

## 5. Database Schema

### 5.1 ERD — Entity List

```
users ──────────────────┬── sessions
  │                     │
  ├── devices ──────────┴── trusted_devices
  │
  ├── files ─────────────── share_links
  │
  ├── transfer_sessions ─── transfer_history
  │
  ├── transfer_requests
  │
  └── notifications
```

### 5.2 SQL Schema

```sql
-- ─────────────────────────────────────────────
-- USERS
-- ─────────────────────────────────────────────
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username        VARCHAR(50)  UNIQUE,
    email           VARCHAR(255) UNIQUE,
    password_hash   VARCHAR(255),
    display_name    VARCHAR(100),
    avatar_url      TEXT,
    plan_type       VARCHAR(20)  NOT NULL DEFAULT 'FREE',
    plan_expires_at TIMESTAMPTZ,
    storage_used    BIGINT       NOT NULL DEFAULT 0,
    storage_limit   BIGINT       NOT NULL DEFAULT 10737418240, -- 10 GB
    is_guest        BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_username ON users(username) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_email    ON users(email)    WHERE deleted_at IS NULL;

-- ─────────────────────────────────────────────
-- SESSIONS
-- ─────────────────────────────────────────────
CREATE TABLE sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token   VARCHAR(512) NOT NULL UNIQUE,
    device_id       UUID,
    ip_address      INET,
    user_agent      TEXT,
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sessions_user_id ON sessions(user_id);
CREATE INDEX idx_sessions_refresh_token ON sessions(refresh_token);

-- ─────────────────────────────────────────────
-- DEVICES
-- ─────────────────────────────────────────────
CREATE TABLE devices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         REFERENCES users(id) ON DELETE CASCADE,
    device_name     VARCHAR(100) NOT NULL,
    device_type     VARCHAR(50)  NOT NULL, -- PHONE, LAPTOP, TABLET, DESKTOP
    platform        VARCHAR(50)  NOT NULL, -- ANDROID, IOS, WINDOWS, MACOS, LINUX, WEB
    device_token    VARCHAR(512) NOT NULL UNIQUE,
    fingerprint     VARCHAR(255),
    last_seen_at    TIMESTAMPTZ,
    last_ip         INET,
    is_online       BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_devices_user_id    ON devices(user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_devices_token      ON devices(device_token);

-- ─────────────────────────────────────────────
-- TRUSTED DEVICES
-- ─────────────────────────────────────────────
CREATE TABLE trusted_devices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_device_id UUID         NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    trusted_device_id UUID       NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    paired_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    trust_level     VARCHAR(20)  NOT NULL DEFAULT 'FULL', -- FULL, READ_ONLY
    UNIQUE(owner_device_id, trusted_device_id)
);

-- ─────────────────────────────────────────────
-- FILES (metadata for stored files)
-- ─────────────────────────────────────────────
CREATE TABLE files (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id        UUID         REFERENCES users(id) ON DELETE SET NULL,
    original_name   VARCHAR(255) NOT NULL,
    mime_type       VARCHAR(127),
    size_bytes      BIGINT       NOT NULL,
    checksum_sha256 VARCHAR(64)  NOT NULL,
    storage_key     TEXT         NOT NULL,  -- path/key in storage provider
    storage_backend VARCHAR(20)  NOT NULL DEFAULT 'LOCAL', -- LOCAL, MINIO, S3
    is_chunked      BOOLEAN      NOT NULL DEFAULT FALSE,
    chunk_count     INT,
    upload_complete BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_files_owner_id   ON files(owner_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_files_storage_key ON files(storage_key);

-- ─────────────────────────────────────────────
-- SHARE LINKS (Mode 4)
-- ─────────────────────────────────────────────
CREATE TABLE share_links (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    short_code      VARCHAR(12)  NOT NULL UNIQUE,
    file_id         UUID         NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    owner_id        UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    password_hash   VARCHAR(255),           -- NULL = no password (premium)
    max_downloads   INT,                    -- NULL = unlimited
    download_count  INT          NOT NULL DEFAULT 0,
    expires_at      TIMESTAMPTZ,            -- NULL = never (premium)
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_share_links_short_code ON share_links(short_code) WHERE is_active = TRUE;
CREATE INDEX idx_share_links_owner_id   ON share_links(owner_id);
CREATE INDEX idx_share_links_expires_at ON share_links(expires_at) WHERE expires_at IS NOT NULL;

-- ─────────────────────────────────────────────
-- TRANSFER SESSIONS
-- ─────────────────────────────────────────────
CREATE TABLE transfer_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mode            VARCHAR(20)  NOT NULL, -- DIRECT, NEARBY, USERNAME, LINK
    sender_id       UUID         REFERENCES users(id) ON DELETE SET NULL,
    receiver_id     UUID         REFERENCES users(id) ON DELETE SET NULL,
    sender_device   UUID         REFERENCES devices(id) ON DELETE SET NULL,
    receiver_device UUID         REFERENCES devices(id) ON DELETE SET NULL,
    file_id         UUID         REFERENCES files(id) ON DELETE SET NULL,
    share_link_id   UUID         REFERENCES share_links(id) ON DELETE SET NULL,
    status          VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    -- PENDING, CONNECTING, IN_PROGRESS, PAUSED, COMPLETED, FAILED, CANCELLED
    bytes_transferred BIGINT     NOT NULL DEFAULT 0,
    total_bytes     BIGINT,
    last_chunk_index INT,
    transfer_token  VARCHAR(512),
    session_code    VARCHAR(12),            -- 6-digit code for Mode 1
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ts_sender_id      ON transfer_sessions(sender_id);
CREATE INDEX idx_ts_receiver_id    ON transfer_sessions(receiver_id);
CREATE INDEX idx_ts_session_code   ON transfer_sessions(session_code) WHERE status IN ('PENDING','CONNECTING');
CREATE INDEX idx_ts_status         ON transfer_sessions(status);

-- ─────────────────────────────────────────────
-- TRANSFER REQUESTS (Mode 3 — Username Share)
-- ─────────────────────────────────────────────
CREATE TABLE transfer_requests (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_session_id UUID         NOT NULL REFERENCES transfer_sessions(id) ON DELETE CASCADE,
    sender_id           UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    receiver_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    -- PENDING, ACCEPTED, REJECTED, EXPIRED
    message             TEXT,
    expires_at          TIMESTAMPTZ  NOT NULL DEFAULT (NOW() + INTERVAL '10 minutes'),
    responded_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tr_receiver_id ON transfer_requests(receiver_id) WHERE status = 'PENDING';

-- ─────────────────────────────────────────────
-- TRANSFER HISTORY
-- ─────────────────────────────────────────────
CREATE TABLE transfer_history (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_session_id UUID         NOT NULL REFERENCES transfer_sessions(id) ON DELETE CASCADE,
    user_id             UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role                VARCHAR(10)  NOT NULL, -- SENDER, RECEIVER
    mode                VARCHAR(20)  NOT NULL,
    file_name           VARCHAR(255),
    file_size           BIGINT,
    status              VARCHAR(30)  NOT NULL,
    peer_display_name   VARCHAR(100),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_th_user_id    ON transfer_history(user_id, created_at DESC);

-- ─────────────────────────────────────────────
-- NOTIFICATIONS
-- ─────────────────────────────────────────────
CREATE TABLE notifications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type        VARCHAR(50)  NOT NULL,
    -- TRANSFER_REQUEST, TRANSFER_COMPLETE, TRANSFER_FAILED, LINK_EXPIRING, SYSTEM
    title       VARCHAR(200) NOT NULL,
    body        TEXT,
    payload     JSONB,
    is_read     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_user_id ON notifications(user_id, is_read, created_at DESC);
```

---

## 6. Transfer Engine Design

### 6.1 Architecture

```
TransferEngine (Orchestrator)
│
├── TransferQueue          → Priority queue, throttle concurrent transfers
├── TransferSession        → State machine per active transfer
├── ChunkedStreamer         → NIO zero-copy (FileChannel.transferTo/From)
├── ResumeManager          → Track chunk index, restart from last ACK'd
├── IntegrityVerifier      → SHA-256 per chunk + whole file
├── ProgressTracker        → Emit progress events via WebSocket
└── RetryPolicy            → Exponential backoff, max retries
```

### 6.2 Transfer State Machine

```
PENDING
   │
   ▼
CONNECTING ──────────────────────────────────────────────► FAILED
   │
   ▼
IN_PROGRESS ─────────────────────────────────────────────► FAILED
   │            │
   │            ▼
   │          PAUSED ◄───────────────────────────────────► CANCELLED
   │            │
   │            ▼
   └───────► COMPLETED
```

### 6.3 Chunked Streaming — Key Implementation Notes

```java
// ChunkedStreamer — Java 25 Virtual Thread + NIO
// Chunk size: 4 MB (configurable)
// Uses FileChannel.transferTo() — zero-copy, no heap allocation
// Each chunk: [chunk_index (4B)] [size (8B)] [data] [sha256 (32B)]

// Resume: client sends last_ack_chunk → server seeks to that offset
// Integrity: per-chunk SHA-256 + final whole-file SHA-256
// Memory: reads one chunk at a time → <100 MB RAM for 100 GB file
```

### 6.4 WebSocket — Progress Events

```json
// Server → Client (progress update)
{
  "event": "TRANSFER_PROGRESS",
  "session_id": "uuid",
  "bytes_transferred": 10485760,
  "total_bytes": 42949672960,
  "percentage": 24.4,
  "speed_bps": 52428800,
  "eta_seconds": 623
}

// Server → Client (status change)
{
  "event": "TRANSFER_STATUS",
  "session_id": "uuid",
  "status": "COMPLETED",
  "checksum": "sha256hex..."
}
```

---

## 7. Device Discovery Design

### 7.1 Discovery Methods

| Method | Scope | Use Case |
|--------|-------|----------|
| mDNS / DNS-SD / Zeroconf | LAN | Nearby Share auto-discovery |
| QR Pairing | Physical proximity | Nearby Share manual |
| Share Code | Any network | Direct Share, Nearby |
| Trusted Device | Pre-paired | Ecosystem (future) |

### 7.2 Services

```
DeviceDiscoveryService
│
├── MdnsService          → Broadcasts/listens _fylo._tcp.local
├── DeviceRegistry       → In-memory map of visible nearby devices
├── PresenceManager      → Online/offline tracking (Redis pub/sub)
├── HeartbeatManager     → Ping every 15s, mark offline after 45s
├── QrPairingService     → Generate/verify QR payload with HMAC
├── CodePairingService   → 6-char alphanumeric pairing codes
└── TrustedDeviceService → Persist trust relationships to DB
```

### 7.3 mDNS Record Format

```
Service type:  _fylo._tcp.local
Instance:      Nisidh's MacBook._fylo._tcp.local
TXT records:
  device_id=<uuid>
  platform=MACOS
  version=1.0.0
  capabilities=SEND,RECEIVE,ECOSYSTEM
```

---

## 8. Authentication & User System

### 8.1 Token Types

| Token | TTL | Storage | Purpose |
|-------|-----|---------|---------|
| Access JWT | 15 min | Memory | API auth |
| Refresh Token | 30 days | DB + HttpOnly Cookie | Re-issue access |
| Device Token | 1 year | DB | Device identity |
| Transfer Token | 10 min | DB | Authorize one transfer |
| Guest Token | 24 hours | Redis | No-login modes |

### 8.2 Auth Flows

**Registered User:**
```
POST /api/v1/auth/register → returns token pair
POST /api/v1/auth/login    → returns token pair
POST /api/v1/auth/refresh  → rotate refresh token
POST /api/v1/auth/logout   → revoke refresh token
```

**Guest (Mode 1 & 2):**
```
POST /api/v1/auth/guest    → returns guest_token (JWT, 24h TTL)
```

### 8.3 JWT Payload

```json
{
  "sub": "user-uuid",
  "username": "aman",
  "plan": "FREE",
  "guest": false,
  "device_id": "device-uuid",
  "iat": 1700000000,
  "exp": 1700000900
}
```

---

## 9. Storage System

### 9.1 Interface

```java
public interface StorageProvider {
    String store(InputStream stream, String key, long size) throws StorageException;
    InputStream retrieve(String key) throws StorageException;
    void delete(String key) throws StorageException;
    boolean exists(String key);
    URL generatePresignedUrl(String key, Duration ttl);  // for S3/MinIO
}
```

### 9.2 Implementations

| Provider | When Used |
|----------|-----------|
| `LocalStorageProvider` | Dev / self-hosted |
| `MinioStorageProvider` | Production self-managed |
| `S3StorageProvider` | Production cloud |

### 9.3 Storage Key Convention

```
files/{year}/{month}/{user_id}/{file_id}/{original_filename}
chunks/{transfer_session_id}/{chunk_index}
```

---

## 10. API Design — Modules & Endpoints

### Base URL: `https://api.fylo.app/api/v1`

#### Authentication
```
POST   /auth/register
POST   /auth/login
POST   /auth/refresh
POST   /auth/logout
POST   /auth/guest
```

#### Users
```
GET    /users/me
PATCH  /users/me
GET    /users/search?q=@username
GET    /users/me/history
GET    /users/me/storage
```

#### Devices
```
POST   /devices/register
GET    /devices
DELETE /devices/{id}
POST   /devices/{id}/trust
DELETE /devices/{id}/trust
GET    /devices/trusted
```

#### Discovery
```
POST   /discovery/code/generate       → generate pairing code
POST   /discovery/code/connect        → connect via code
POST   /discovery/qr/generate         → generate QR payload
POST   /discovery/qr/verify           → verify QR scan
GET    /discovery/nearby              → list nearby discovered devices
```

#### Transfers
```
POST   /transfers/direct/initiate     → Mode 1: sender creates session + code
POST   /transfers/direct/join         → Mode 1: receiver joins via code
POST   /transfers/nearby/initiate     → Mode 2: initiate to paired device
POST   /transfers/username/request    → Mode 3: send request to @username
POST   /transfers/username/respond    → Mode 3: accept / reject
GET    /transfers/{id}                → session status
POST   /transfers/{id}/cancel
POST   /transfers/{id}/pause
POST   /transfers/{id}/resume
```

#### Uploads (chunked)
```
POST   /uploads/init                  → create upload session, get upload_id
PUT    /uploads/{upload_id}/chunk/{n} → upload chunk n
POST   /uploads/{upload_id}/complete  → finalize, verify whole-file checksum
GET    /uploads/{upload_id}/status    → resume support
DELETE /uploads/{upload_id}           → cancel upload
```

#### Downloads
```
GET    /downloads/{transfer_session_id}           → stream file
GET    /downloads/{transfer_session_id}/chunk/{n} → single chunk
HEAD   /downloads/{transfer_session_id}           → metadata only
```

#### Share Links (Mode 4)
```
POST   /links                    → create link
GET    /links                    → list my links
GET    /links/{code}             → link info (public)
GET    /links/{code}/download    → download (public)
DELETE /links/{code}             → revoke
PATCH  /links/{code}             → update expiry / password
GET    /links/{code}/analytics   → download stats (premium)
```

#### Transfer Requests
```
GET    /requests                 → pending incoming requests
POST   /requests/{id}/accept
POST   /requests/{id}/reject
```

#### Notifications
```
GET    /notifications
POST   /notifications/mark-read
DELETE /notifications/{id}
WS     /ws/notifications         → real-time push
```

#### History
```
GET    /history?page=0&size=20&mode=DIRECT
DELETE /history/{id}
```

---

## 11. Transfer Modes — Detailed Flows

### Mode 1 — Direct Share

```
Sender                    Server                    Receiver
  │                         │                          │
  ├── POST /transfers/       │                          │
  │   direct/initiate ──────►│                          │
  │                         │ Create TransferSession   │
  │◄── {session_code:"ABC123", transfer_token}         │
  │                         │                          │
  │   [Share code/QR]       │       [User B scans]     │
  │                         │                          │
  │                         │◄─── POST /transfers/─────┤
  │                         │     direct/join          │
  │                         │     {code:"ABC123"}      │
  │                         │                          │
  │◄── WS: RECEIVER_JOINED  │  WS: SESSION_READY ─────►│
  │                         │                          │
  ├── PUT /uploads/{id}/chunk/0 ──────────────────────►│
  ├── PUT /uploads/{id}/chunk/1 ──────────────────────►│
  │          ...            │                          │
  ├── POST /uploads/{id}/complete                      │
  │                         │  WS: TRANSFER_COMPLETE ─►│
```

### Mode 3 — Username Share

```
Sender                    Server                    Receiver (@aman)
  │                         │                          │
  ├── POST /transfers/       │                          │
  │   username/request ─────►│                          │
  │   {to:"@aman", file}    │                          │
  │                         │ Create TransferRequest   │
  │                         ├── WS: TRANSFER_REQUEST ─►│
  │                         │                          │
  │                         │◄── POST /requests/{id}/──┤
  │                         │    accept                │
  │                         │                          │
  │◄── WS: REQUEST_ACCEPTED │  Create TransferSession  │
  │                         │                          │
  │   [Chunked upload begins as in Mode 1]             │
```

### Mode 4 — Link Share

```
Owner                     Server                    Downloader
  │                         │                          │
  ├── POST /uploads/init ───►│                          │
  ├── PUT  /uploads/.../    │                          │
  │    chunk/N (loop) ──────►│                          │
  ├── POST /uploads/complete►│                          │
  │                         │ store file in S3/MinIO   │
  │◄── {file_id}            │                          │
  │                         │                          │
  ├── POST /links ──────────►│                          │
  │   {file_id, expires_in} │                          │
  │◄── {url:"fylo.app/s/    │                          │
  │      abc123"}           │                          │
  │                         │                          │
  │   [Share link via any channel]                     │
  │                         │◄── GET /links/abc123/────┤
  │                         │    download              │
  │                         ├── stream from S3 ───────►│
  │                         │   (range requests OK)    │
```

---

## 12. Security Architecture

### 12.1 Layers

| Layer | Control |
|-------|---------|
| Transport | TLS 1.3 (all endpoints) |
| API Auth | JWT (RS256) |
| Transfer Auth | Short-lived Transfer Token (per session) |
| Device Auth | Device Token (long-lived) |
| Rate Limiting | Per-IP + Per-User (Redis token bucket) |
| Replay Protection | JWT `jti` claim + Redis blacklist |
| Path Traversal | Sanitize all file paths, never trust client paths |
| File Validation | MIME type check, magic bytes check |
| Chunk Integrity | SHA-256 per chunk; reject & request resend on mismatch |
| Link Protection | Optional bcrypt password on share links (premium) |
| Storage | Pre-signed URLs (S3/MinIO) for direct downloads |

### 12.2 Rate Limits (defaults)

| Endpoint Group | Free | Premium |
|----------------|------|---------|
| Auth endpoints | 10/min | 10/min |
| Transfer initiations | 20/hour | 200/hour |
| Link creation | 10/day | 1000/day |
| Chunk uploads | 1000/min | 10000/min |
| API global | 500/min | 5000/min |

---

## 13. Plans & Entitlements

### 13.1 Free Plan

```yaml
direct_share:     unlimited
nearby_share:     unlimited
username_share:
  concurrent:     2
  history:        last 30 entries
link_share:
  link_expiry:    2 days
  max_file_size:  10 GB
  storage:        10 GB total
  active_links:   10
  password:       false
  analytics:      false
```

### 13.2 Premium Plan

```yaml
link_share:
  link_expiry:    [7d, 30d, 90d, never]
  max_file_size:  100 GB+
  storage:        [500 GB, 1 TB, 2 TB]
  active_links:   unlimited
  password:       true
  analytics:      true
  download_limit: true
  custom_expiry:  true
username_share:
  concurrent:     unlimited
  priority:       true
history:          unlimited
ecosystem:
  clipboard_sync: true
  notification_sync: true
  photo_sync:     true
  folder_sync:    true
  cross_device:   true
```

---

## 14. Performance Targets & Scaling

### 14.1 Targets

```
Concurrent transfers:    1,000+
Max file size:           100 GB+
RAM per 40 GB transfer:  < 100 MB
Chunk size:              4 MB (configurable 1–16 MB)
Throughput:              Limited by network, not server
API P99 latency:         < 200 ms (non-transfer)
```

### 14.2 Java 25 Concurrency Model

```java
// Virtual Thread executor — one thread per transfer, cheap
ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();

// Structured Concurrency — upload + integrity check in parallel
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Future<Void> upload    = scope.fork(() -> streamer.stream(chunk));
    Future<Void> checksum  = scope.fork(() -> verifier.verify(chunk));
    scope.join().throwIfFailed();
}
```

### 14.3 Horizontal Scaling

```
Load Balancer (sticky sessions for WebSocket via Redis)
       │
   ┌───┴───┐
  App 1   App 2   App N          ← Stateless (JWT, Redis sessions)
   │       │       │
   └───────┴───────┘
           │
      PostgreSQL (Primary + Read Replicas)
           │
        MinIO (clustered) / S3
           │
         Redis (Cluster mode)
```

### 14.4 Observability

```
Metrics:    Micrometer → Prometheus → Grafana
Tracing:    OpenTelemetry → Jaeger / Tempo
Logging:    Logback → ELK / Loki
Alerting:   Grafana Alerts / PagerDuty
Health:     /actuator/health (Spring Boot Actuator)
```

---

## 15. Docker & Infrastructure Setup

### 15.1 docker-compose.yml (Development)

```yaml
version: "3.9"
services:

  fylo-backend:
    build: .
    ports: ["8080:8080"]
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/fylo
      SPRING_REDIS_HOST: redis
      STORAGE_PROVIDER: minio
      MINIO_ENDPOINT: http://minio:9000
    depends_on: [postgres, redis, minio]

  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: fylo
      POSTGRES_USER: fylo
      POSTGRES_PASSWORD: fylo_secret
    volumes: [postgres_data:/var/lib/postgresql/data]
    ports: ["5432:5432"]

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    command: redis-server --appendonly yes
    volumes: [redis_data:/data]

  minio:
    image: minio/minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: fylo
      MINIO_ROOT_PASSWORD: fylo_secret
    volumes: [minio_data:/data]
    ports: ["9000:9000", "9001:9001"]

volumes:
  postgres_data:
  redis_data:
  minio_data:
```

### 15.2 Dockerfile

```dockerfile
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./gradlew bootJar -x test

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/fylo.jar fylo.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseVirtualThreads", \
            "-XX:MaxRAMPercentage=75.0", \
            "-jar", "fylo.jar"]
```

---

## 16. CI/CD Pipeline

```yaml
# .github/workflows/ci.yml
name: Fylo CI/CD

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16
        env: {POSTGRES_DB: fylo_test, POSTGRES_USER: fylo, POSTGRES_PASSWORD: test}
        options: --health-cmd pg_isready
      redis:
        image: redis:7
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: {java-version: '25', distribution: 'temurin'}
      - name: Run tests
        run: ./gradlew test jacocoTestReport
      - name: Upload coverage
        uses: codecov/codecov-action@v4

  build:
    needs: test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Build Docker image
        run: docker build -t fylo-backend:${{ github.sha }} .
      - name: Push to registry
        run: |
          docker tag fylo-backend:${{ github.sha }} ghcr.io/fylo/backend:${{ github.sha }}
          docker push ghcr.io/fylo/backend:${{ github.sha }}

  deploy-staging:
    needs: build
    if: github.ref == 'refs/heads/develop'
    runs-on: ubuntu-latest
    steps:
      - name: Deploy to staging
        run: kubectl set image deployment/fylo-backend fylo-backend=ghcr.io/fylo/backend:${{ github.sha }}

  deploy-production:
    needs: build
    if: github.ref == 'refs/heads/main'
    environment: production
    runs-on: ubuntu-latest
    steps:
      - name: Deploy to production
        run: kubectl set image deployment/fylo-backend fylo-backend=ghcr.io/fylo/backend:${{ github.sha }}
```

---

## 17. Monitoring & Observability

### 17.1 Key Metrics to Track

```
fylo_transfer_active_total          # Gauge: active transfers
fylo_transfer_bytes_total           # Counter: bytes transferred
fylo_transfer_duration_seconds      # Histogram: transfer durations
fylo_transfer_errors_total          # Counter: by error type
fylo_upload_chunk_size_bytes        # Histogram: chunk sizes
fylo_link_downloads_total           # Counter: link share downloads
fylo_auth_login_total               # Counter: by success/fail
fylo_websocket_connections_active   # Gauge: active WS connections
fylo_storage_used_bytes             # Gauge: by user/total
jvm_memory_used_bytes               # JVM heap
jvm_threads_live_total              # Thread count (virtual + carrier)
```

### 17.2 Alerting Rules

```yaml
- alert: HighTransferErrorRate
  expr: rate(fylo_transfer_errors_total[5m]) > 0.05
  for: 2m

- alert: HighMemoryUsage
  expr: jvm_memory_used_bytes / jvm_memory_max_bytes > 0.85
  for: 5m

- alert: DatabaseConnections
  expr: pg_stat_activity_count > 80
  for: 2m
```

---

## 18. Testing Strategy

### 18.1 Layers

| Type | Tool | Coverage Target |
|------|------|----------------|
| Unit | JUnit 5 + Mockito | 80%+ |
| Integration | Testcontainers (Postgres, Redis, MinIO) | All services |
| API/E2E | RestAssured + WireMock | All endpoints |
| Transfer Engine | Custom test harness (large file simulation) | All scenarios |
| Performance | Gatling | Transfer throughput |
| Security | OWASP ZAP | All endpoints |

### 18.2 Critical Test Scenarios

```
✓ 40 GB file transfer completes without OOM
✓ Resume from chunk 500 of 10,000
✓ Concurrent 100 transfers do not interfere
✓ Link expiry enforced correctly
✓ Guest token cannot access authenticated endpoints
✓ Rate limits trigger correctly
✓ Checksum mismatch causes chunk retry
✓ Sender disconnect in Mode 1 makes transfer unavailable
✓ Mode 3 request expires after 10 minutes
✓ Storage limit enforced on upload
```

---

## 19. Migration Plan

### Phase 1 — Foundation (Week 1–2)
- [ ] Set up project structure (Gradle multi-module)
- [ ] Configure Java 25, Virtual Threads, Spring Boot 3.x
- [ ] Implement database schema + Flyway migrations
- [ ] Auth module (JWT, guest tokens, refresh)
- [ ] Basic User & Device registration

### Phase 2 — Transfer Engine (Week 3–4)
- [ ] `ChunkedStreamer` with NIO FileChannel
- [ ] `ResumeManager` + `IntegrityVerifier`
- [ ] `TransferQueue` + `ProgressTracker`
- [ ] WebSocket progress events
- [ ] Mode 1 (Direct Share) — full flow

### Phase 3 — Discovery & Nearby (Week 5)
- [ ] mDNS / DNS-SD service
- [ ] QR Pairing + Code Pairing
- [ ] `TrustedDeviceService`
- [ ] Mode 2 (Nearby Share) — full flow

### Phase 4 — Storage & Links (Week 6)
- [ ] `StorageProvider` abstraction
- [ ] MinIO integration
- [ ] Mode 4 (Link Share) — full flow
- [ ] Link expiry, analytics, password protection

### Phase 5 — Username Share & Notifications (Week 7)
- [ ] Username search
- [ ] Transfer request flow
- [ ] WebSocket notifications
- [ ] Mode 3 (Username Share) — full flow

### Phase 6 — Production Hardening (Week 8–9)
- [ ] Rate limiting (Redis token bucket)
- [ ] Security audit (path traversal, MIME validation)
- [ ] Performance benchmarks (40 GB, <100 MB RAM)
- [ ] Horizontal scaling tests
- [ ] Prometheus + Grafana dashboards
- [ ] Docker Compose (dev) + Kubernetes manifests (prod)

### Phase 7 — Plans & Polish (Week 10)
- [ ] Plan entitlement enforcement
- [ ] Premium features (password links, analytics)
- [ ] OpenAPI / Swagger generation
- [ ] Full E2E test suite
- [ ] Load testing with Gatling

---

## 20. Deliverable Checklist

### Architecture
- [ ] System architecture diagram
- [ ] ER diagram (database)
- [ ] Class diagrams (Transfer Engine, Auth, Storage)
- [ ] Sequence diagrams (all 4 modes)
- [ ] Deployment architecture diagram

### Code
- [ ] Java 25 project skeleton (all packages)
- [ ] Transfer Engine core (ChunkedStreamer, ResumeManager)
- [ ] Discovery Engine (mDNS, QR, Code pairing)
- [ ] Auth module (JWT, refresh, guest)
- [ ] Storage abstraction + MinIO/S3 impl
- [ ] All REST controllers (skeletons)
- [ ] WebSocket handlers

### Database
- [ ] Full PostgreSQL schema (SQL file)
- [ ] Flyway migration scripts
- [ ] Indexes + constraints

### API
- [ ] OpenAPI 3.1 specification (fylo-openapi.yaml)
- [ ] Swagger UI setup

### Infrastructure
- [ ] Dockerfile
- [ ] docker-compose.yml (dev)
- [ ] Kubernetes manifests (prod)
- [ ] CI/CD GitHub Actions workflow
- [ ] Prometheus + Grafana config
- [ ] Alerting rules

### Testing
- [ ] Unit test skeletons
- [ ] Integration test config (Testcontainers)
- [ ] Transfer Engine stress test harness
- [ ] Gatling performance test script

---

*Last updated: June 2025 — Fylo Platform v1.0 Backend Backbone*
