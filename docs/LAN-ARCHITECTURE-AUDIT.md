# Fylo LAN Discovery and P2P Architecture Audit

## Scope and evidence

This is a read-only, pre-migration architecture audit of the repository at
commit `f5c9f7a`. Its route, port, and service-name findings describe that
baseline and are intentionally retained as historical evidence; consult
`LAN-AGENT.md` for the current contract.
No source code, configuration, test, migration, or deployment file was changed.
The audit inspected the Java backend, tests, Next.js UI, Maven and npm configuration,
Docker and Kubernetes files, database migrations, OpenAPI, startup scripts, README,
and the existing architecture documentation.

Evidence is classified as:

- **Verified**: directly supported by source or a test run.
- **Documented**: stated by repository documentation but not necessarily implemented.
- **UNKNOWN - requires runtime verification**: the repository does not prove the behavior.

The focused command `mvn -q -Dtest=LanModeIntegrationTest test` passed. The test runs
two `FileController` instances in one JVM, exercises real HTTP control traffic and
real binary transfer traffic, and verifies SHA-256 output. It seeds each registry
directly because CI does not provide usable mDNS multicast. It is not a two-physical-
device or firewall test. The test output also showed that WebSocket startup can hit an
adjacent-port collision, which `FileController.start()` logs and tolerates.

## Executive answer

### Does current Fylo perform LAN discovery?

Yes. The implementation uses **mDNS/DNS-SD over multicast**, through JmDNS. It does
not use UDP broadcast, IP scanning, a custom discovery packet, or WebSocket discovery.
There is also an explicit HTTP manual-connect fallback.

### Is current LAN file transfer actually P2P?

Yes for the file bytes. The path is:

```text
Browser A -> Backend A /upload
          -> Backend A FileSender on a dynamic TCP port
Backend A -> HTTP JSON /lan/offer -> Backend B
Backend B -> TCP PeerLink connection -> Backend A FileSender
Backend A -> Backend B       (file bytes)
Backend B -> local Downloads
```

The cloud is not involved in this Nearby path. The browser does not connect directly
to the peer. Backend A and Backend B are both the local device nodes.

### What prevents the target production architecture today?

The main blocker is not the byte-transfer engine. It is the boundary between a local
agent and a cloud backend. LAN discovery, LAN control, pairing, transfer state, and
the browser gateway are currently co-located in the same Java backend. The LAN control
plane is plain, unauthenticated HTTP; device identity is a persisted UUID, not a
cryptographic identity; and the UI assumes its own Java backend is reachable through
localhost. The existing transfer engine is a strong extraction seam, so a staged
local-agent split is preferable to a rewrite.

## 1. Current runtime shape

### Process and listener ownership

`p2p.App.main()` creates one `p2p.controller.FileController`, starts it, and waits on
stdin. `FileController` creates:

| Surface | Owner | Current port behavior |
|---|---|---|
| HTTP API and gateway | `com.sun.net.httpserver.HttpServer` | CLI argument, then `PORT`, then `7000` in `App` |
| WebSocket events | `p2p.ws.WebSocketServer` | API port + 1 |
| mDNS/DNS-SD | `p2p.device.DeviceDiscoveryService` / JmDNS | mDNS multicast, service advertises API port |
| File data | `p2p.transfer.FileSender` | one dynamic TCP port per offered file, selected by `UploadUtils` |

`FileController` is both composition root and gateway for `/upload`, `/download`,
`/lan/*`, `/transfers`, metrics, and `/api/v1/*`. The frontend is a separate Next.js
process whose rewrites point to the backend.

### Deployment drift relevant to LAN

The default port is inconsistent across repository surfaces:

- `App` defaults to `7000`.
- `ui/next.config.js` defaults to `http://localhost:7000`.
- `README.md`, OpenAPI, Docker, and much deployment documentation assume `9090`.
- `Dockerfile.backend` exposes `9090` but starts `p2p.App` without an argument, so it
  defaults to `7000` unless `PORT` is supplied.
- Compose publishes `9090:9090`, not `7000:9090` or `7000:7000`.
- Dynamic `FileSender` ports are only declared with Docker `expose`, not published for
  external LAN peers.
- The WebSocket port is not published by Compose.


//pehle port 9090 ya 9091 use kar re the ab 7000 use kar re hai 
These are deployment blockers for a packaged local agent, independent of the protocol.

## 2. Exact discovery implementation

### Code path

```text
App.main
  -> FileController constructor
       -> DeviceIdentity.loadOrCreate(dataDir)
       -> DeviceRegistry(dataDir)
       -> DeviceDiscoveryService(identity, registry, boundPort)
       -> DevicePresenceManager(registry, HeartbeatService, 10)
  -> FileController.start()
       -> server.start()
       -> presenceManager.start()
       -> virtual thread: discoveryService.start()
            -> pickSiteLocalAddress()
            -> JmDNS.create(bindAddress)
            -> registerService(_air._tcp.local., API port, TXT)
            -> addServiceListener()
                 -> serviceAdded(): requestServiceInfo(..., 1000 ms)
                 -> serviceResolved(): onResolved(ServiceInfo)
                      -> DeviceRegistry.upsertOnline(... address, port)
                 -> serviceRemoved(): DeviceRegistry.markOffline(deviceId)
```

The controlling implementation is `src/main/java/p2p/device/DeviceDiscoveryService.java`.
The service type is the code constant `_air._tcp.local.`. JmDNS handles the DNS-SD
registration/browse and mDNS multicast transport.

### Discovery message and advertised fields

There is no application-owned UDP packet format. The DNS-SD service instance name is:

```text
<device name>-<first eight characters of device UUID>
```

The TXT records are:

```text
id=<persistent device UUID>
name=<device display name>
os=<System os.name>
type=<derived device type>
version=1.0.0
capabilities=SEND,RECEIVE,ECOSYSTEM
```

The DNS-SD service port is the backend HTTP API port. `serviceResolved()` obtains the
first IPv4 address from `ServiceInfo.getInet4Addresses()` and stores it with the
advertised port. The local implementation prefers the first up, non-loopback,
non-virtual, site-local IPv4 interface when creating JmDNS.

### Timing, liveness, and expiration

- mDNS service resolution requests use a `1000` millisecond JmDNS request timeout.
- No application discovery retry interval is configured in `DeviceDiscoveryService`;
  retry and browse behavior are delegated to JmDNS.
- Presence sweep interval is `10` seconds.
- `HeartbeatService` uses a 3-second HTTP connect timeout and a 3-second request
  timeout for `GET /lan/ping`.
- `DevicePresenceManager` forgives one failed heartbeat and marks an online device
  offline after two consecutive failures, normally about 20 seconds plus request
  timing.
- mDNS removal immediately marks a known device offline.
- Known records do not expire or get deleted. `DeviceRegistry` persists them and
  reloads them offline after restart.
- An offline device is not actively re-discovered by the heartbeat manager; mDNS,
  manual connect, or incoming traffic must prove it online again.

### What discovery can and cannot cross

mDNS is local-link multicast discovery. It should work on a normal LAN where multicast
and client-to-client traffic are permitted. It does not discover across routed
subnets without a multicast reflector or another rendezvous mechanism. Guest Wi-Fi,
AP/client isolation, VPN routing, WSL2 NAT, and host firewalls can block either the
multicast or the later TCP connections. The repository provides `POST /lan/connect`
as an IP-and-port fallback.

Discovery requires the backend process on both devices. It does not require the cloud,
does not depend on the browser, and does not depend on the Next.js process. It is
automatic when mDNS works; manual configuration is available when it does not.

### Documentation mismatch

The implementation uses `_air._tcp.local.` and TXT key `version`. `docs/LAN-MODE.md`
describes `_peerlink._tcp.local.` and refers to a `v` field. The source is the
authoritative behavior for this audit; this mismatch must be removed before external
agents implement compatible discovery.

//hamne fylo ka naam change kar ke air rakha diya hai to sahyad documentation update nahihue hai 

## 3. Complete Nearby Devices flow

### Device A discovering Device B

1. Device A starts `FileController`; `start()` launches `DeviceDiscoveryService` on a
   virtual thread and registers A's `_air._tcp.local.` record.
2. Device B does the same. JmDNS on A receives B's service-added event.
3. A requests service info. JmDNS resolves B's TXT fields, IPv4 address, and API port.
4. `DeviceDiscoveryService.onResolved()` ignores malformed records and A's own UUID,
   then calls `DeviceRegistry.upsertOnline()` with B's ID, name, OS, type, host, and
   API port.
5. `DeviceRegistry` stores B in memory and persists the record to `devices.properties`.
6. `DevicePresenceManager` periodically calls `HeartbeatService.ping()` against B's
   `/lan/ping`. A successful response must be HTTP 200 and contain B's expected ID.
7. The browser on A polls `GET /api/lan/devices` every three seconds in
   `ui/src/components/NearbyDevices.tsx`. Next's rewrite maps this to A's
   `GET /lan/devices`.
8. `LanHandler` returns A's self record plus `deviceRegistry.snapshots()`. The UI
   displays name, OS, online state, last-seen time, and trusted flag.
9. A user selects files. The browser uploads each file to A's `/api/upload`, which is
   rewritten to A's `/upload`. A's `FileSharer` starts a `FileSender` for each file.
10. The UI posts `{deviceId, ports}` to A's `/api/lan/send`.
11. `LanShareService.send()` resolves B from A's registry, obtains each
    `FileSharer.ShareInfo`, tracks outgoing transfers, and uses `ControlPlaneClient`
    to POST an `OfferRequest` to B's `/lan/offer`.
12. B's `OfferManager.register()` uses the TCP source address as `senderHost`, stores
    the offer, marks A online, and auto-accepts only if A's submitted device ID is
    already trusted.
13. Otherwise B's UI polls `/api/lan/offers` every three seconds and shows
    `IncomingOffers.tsx`.
14. On accept, `OfferManager.accept()` creates one receive task per file. Each task
    uses `TransferManager` and `FileReceiver` to connect to A's advertised file port.
15. `PeerClient.connect()` opens a direct TCP socket to A, sends the file token in a
    `HELLO`, and receives the manifest only after token validation.
16. B downloads, verifies, and moves each file into its Downloads directory. B posts
    `accepted`, then `completed` or `failed` to A's `/lan/offer-result`.
17. A's `LanShareService.onOfferResult()` updates the sender-side queue view.

### Manual path

`NearbyDevices.tsx` accepts `host[:port]`. `POST /lan/connect` calls
`ControlPlaneClient.exchangeHello()`, which POSTs `/lan/hello` to the peer. The remote
side records the caller using the actual TCP source address and returns its identity.
Both registries are therefore populated without mDNS.

## 4. Actual file-byte path

### LAN / Nearby mode

Verified from `LanShareService`, `OfferManager`, `TransferManager`, `FileReceiver`,
`PeerClient`, and `FileSender`, and covered by `LanModeIntegrationTest`:

```text
Browser A
  -- multipart HTTP --> Backend A /upload
  -- /lan/send ------> Backend A
Backend A
  -- JSON /lan/offer -> Backend B
Backend B
  -- TCP PeerLink ---> Backend A:<dynamic FileSender port>
Backend A
  -- raw file bytes -> Backend B
Backend B
  -- local disk ----> Downloads/PeerLink
```

The cloud is not on this path. The browser is not on the data path after upload.
The sender's initial browser upload is local to Backend A and is temporary local
staging, not a cloud upload.

### Internet invite mode

The path differs by client type. A browser receiver calls Backend B's
`/download/{port}`. `FileController.DownloadHandler` creates a `PeerClient` to
`localhost:<port>` on Backend B and streams the peer response to the browser. The
actual sender bytes still come from the sender's `FileSender`; there is no cloud
storage hop for a live invite share. Cloud-backed Link mode is separate and uses the
configured `StorageProvider`.

## 5. Responsibilities of existing LAN classes

| Class | Purpose and callers | Network/state | Cloud/UI dependency | Target placement |
|---|---|---|---|---|
| `DeviceDiscoveryService` | JmDNS registration/browse; started by `FileController` | mDNS multicast; no persistent network state; writes registry indirectly | No cloud; UI sees registry through `/lan/devices` | **LOCAL AGENT** |
| `DevicePresenceManager` | 10-second heartbeat scheduler; started by `FileController` | HTTP `/lan/ping`; miss count in memory; registry persists last seen | No cloud; indirect UI dependency | **LOCAL AGENT** |
| `DeviceRegistry` | Known peer records, online flag, last-seen, trusted flag | Concurrent map plus `devices.properties` | No cloud; served by LAN API | **LOCAL AGENT**, later split owner/trust sync |
| `HeartbeatService` | Liveness check for a `DeviceInfo` | Plain HTTP GET, 3-second timeouts | No cloud or UI | **LOCAL AGENT** |
| `DeviceIdentity` | Stable UUID, display name, OS, type | `identity.properties`; UUID only | No cloud; pairing/API expose it | **LOCAL AGENT**, later bind to account cryptographically |
| `LanShareService` | Sender offer creation and sender status tracking | Device-to-device JSON; outgoing offer map in memory | No cloud; called by `/lan/send` | **LOCAL AGENT** |
| `ControlPlaneClient` | JSON POSTs for hello, offer, result | Plain HTTP, 5-10 second timeouts | No cloud; no UI direct call | **LOCAL AGENT**, redesign auth/TLS |
| `LanMessages` | JSON records for offer/result/hello | No state or network by itself | No cloud/UI dependency | **SHARED PROTOCOL**, version and validate |
| `OfferManager` | Receiver offer registry, approval, trust auto-accept, receive enqueue | Offers in memory; downloads on local disk; direct peer TCP via receiver | UI polls it; no cloud | **LOCAL AGENT** |
| `PeerClient` | Authenticated binary client used by receivers and HTTP gateway | Direct TCP socket; token, manifest, ranges, checksums | No cloud; gateway can call it | **LOCAL AGENT / SHARED ENGINE** |
| `PeerLinkProtocol` | Binary frame constants, framing, validation, errors | TCP protocol; no persistent state | No cloud/UI dependency | **SHARED PROTOCOL** |
| `TransferManifest` | File metadata, UUID, size, chunk size, SHA-256, metadata | Serialized in MANIFEST frame | No cloud/UI dependency | **SHARED PROTOCOL** |
| `TransferTokens` | 128-bit random bearer tokens and constant-time compare | Per-offer token, not persisted by service | No cloud/UI dependency | **LOCAL AGENT / SHARED ENGINE**, augment with expiry |
| `TransferManager` | Queue, max 3 active receives, pause/resume/cancel, progress | In-memory queue; `.part`/`.resume` files via receiver | WebSocket notifier and polling UI | **LOCAL AGENT**, expose agent API |
| `FileSharer` | Active share registry and per-file sender creation | In-memory shares; dynamic TCP listeners | Called by upload/direct/LAN paths | **LOCAL AGENT** |
| `FileSender` / `FileReceiver` | Actual direct data-plane send/receive | TCP; local file handles and receiver partials | No cloud; UI sees status indirectly | **LOCAL AGENT / SHARED ENGINE** |
| `QrPairingService` | Two-minute one-time secret and trust insertion | Pending secrets in memory; registry trust persisted | API route; no cloud | **LOCAL AGENT**, require authenticated account association |
| `TrustedDeviceService` | Policy facade for boolean trust and auto-accept | Delegates to registry | API route; currently no auth enforcement | **LOCAL AGENT**, cloud may store policy metadata |
| `FileSafetyService` | Filename/executable policy for stored link uploads | Pure service | Cloud/link path, not Nearby path | **CLOUD BACKEND** for cloud uploads; local agent needs equivalent policy |
| `RateLimiter` | HTTP rate policy, Redis optional | In-memory or Redis | API/security infrastructure | **SPLIT**: cloud API and agent API policies |
| `WebSocketNotifier` / `WebSocketServer` | Progress/status and account notifications | TCP WebSocket on API+1; optional Redis pub/sub | Current UI does not consume it | **SPLIT**: local agent progress channel; cloud user notifications |
| `TransferEngine` | Unified offer and receive facade | Delegates to FileSharer/TransferManager/storage | Cloud Link mode and local modes | **SPLIT**: local data engine plus cloud stored-transfer adapter |
| `StorageProvider` implementations | Local, MinIO, or S3 durable cloud bytes | Filesystem or object storage | Cloud Link mode | **CLOUD BACKEND**; local agent may keep local staging only |

## 6. Identity, pairing, and security audit

### Distinguish the security layers

| Layer | Current behavior | Assessment |
|---|---|---|
| Discovery | TXT fields include a UUID and descriptive metadata; no signature | Discovery is unauthenticated and spoofable |
| Authentication | File data connection requires a 128-bit per-share token in `HELLO` | Good bearer-token gate for the data socket, but no expiry or peer identity binding |
| Authorization | Receiver approval is required unless registry says sender ID is trusted | Consent exists, but trust can be set through weakly protected routes |
| Encryption | LAN control and PeerLink use plain HTTP/TCP | No transport confidentiality or peer authentication |
| Pairing | One-time 128-bit secret, two-minute pending window | Useful bootstrap, but not a cryptographic key exchange |
| Trust | Boolean `trusted` flag keyed by submitted device UUID | Persistent policy exists, but identity proof is insufficient |

### Device identity

`DeviceIdentity.loadOrCreate()` writes a random Java `UUID` and hostname-derived name
to `identity.properties`. The UUID survives restart only if the data directory is
preserved. There is no public/private keypair, certificate, key rotation, signature,
or secure keystore. The identity file is ordinary properties data.

### Pairing

`QrPairingService.start()` generates a UUID pairing ID and `TransferTokens.generate()`
secret, with a two-minute TTL, in an `air://pair/...` payload containing device ID and
API port. `complete()` removes the pending entry atomically, compares the secret in
constant time, then inserts the submitted peer identity and marks that submitted ID
trusted.

The implementation does not prove possession of the peer's long-lived identity: the
caller supplies `deviceId`, name, OS, type, and API port in the request. The code marks
the device holding the pairing window's registry trusted; it does not itself perform
a reciprocal trust write on the other device. The documentation's claim that both
sides are marked trusted is therefore not fully implemented.

### Important security findings

1. `GET /lan/ping`, `/lan/devices`, `/lan/hello`, `/lan/offer`, `/lan/offer-result`,
   and offer actions are not authenticated or TLS-protected.
2. The `/api/v1/pair/*` and `/api/v1/devices/*/trust` route cases do not call the
   `require(exchange)` helper, unlike authenticated account routes.
3. A caller can submit a chosen device ID to `/api/v1/devices/{id}/trust` and the
   service sets trust without proving ownership of that device or user.
4. A caller with LAN access can submit an offer containing arbitrary sender ID,
   callback API port, file ports, bearer tokens, names, and sizes. The TCP source
   address is used for the sender host, which prevents one address field spoof, but
   does not authenticate the sender identity or validate all payload relationships.
5. An attacker cannot read a `FileSender` manifest without its random token, but an
   attacker on the LAN can connect to every dynamic transfer port and attempt token
   guesses. There is no TLS and no rate limiting on the raw PeerLink listener.
6. Discovery records are advisory, not an authentication boundary. A malicious LAN
   host can publish a fake service name/TXT record and cause a false Nearby entry.
7. `TransferTokens` are random 128-bit hex values and compared in constant time,
   which is a good primitive. They are not visibly expired or revoked when a share
   remains active.
8. `FileReceiver.sanitizeFilename()` and OfferManager's per-segment directory
   sanitization are good local path defenses. TransferManifest SHA-256 and chunk
   CRC32C provide integrity checks, not sender authentication or confidentiality.
9. `FileSafetyService` protects cloud/link upload policy, but Nearby offers do not
   invoke it for executable blocking. Nearby relies on user consent and path safety.

## 7. Current LAN protocol

### Control-plane flow

Only these stages exist in source:

```text
mDNS/DNS-SD or manual connect
        -> HELLO over HTTP (manual path)
        -> OFFER over HTTP
        -> ACCEPT or REJECT over HTTP
        -> MANIFEST / RANGE / DATA / COMPLETE over PeerLink TCP
        -> OFFER-RESULT accepted/completed/failed over HTTP
```

There is no separate LAN handshake or authenticated pairing exchange in the offer
path. QR pairing is an API feature and is not automatically required before offers.

### JSON records

`LanMessages` defines:

- `Hello(deviceId, name, os, deviceType, apiPort)`
- `OfferRequest(offerId, deviceId, deviceName, os, deviceType, apiPort, files)`
- `OfferFile(name, size, port, token, relativePath)`
- `OfferResult(offerId, status)` where status is `accepted`, `rejected`, `completed`,
  or `failed`.

Serialization is Jackson JSON over HTTP. `ControlPlaneClient` uses `http://`, not
`https://`, with 5-second connect timeout and 10-second request timeout. There is no
message signature, nonce, replay protection, schema version, or explicit retry loop.
The result callback is best effort.

### PeerLink data-plane flow

`PeerLinkProtocol` uses a big-endian frame:

```text
MAGIC 4 bytes | VERSION 1 | TYPE 1 | PAYLOAD_LENGTH 4 | PAYLOAD
```

Magic is `0x504C4E4B` (`PLNK`), version is `1`, and control payloads are capped at
1 MiB. Bulk bytes are unframed between `DATA_START` and `COMPLETE`.

| Type   | Name                       | Direction        | Payload                                             |
|--------|----------------------------|------------------|-----------------------------------------------------|
| `0x01` | `HELLO`                    | client -> sender | length-prefixed token                               |
| `0x02` | `MANIFEST`                 | sender -> client | UUID, filename, size, chunk size, SHA-256, metadata |
| `0x03` | `RANGE_REQUEST`            | client -> sender | offset and length; `-1` means EOF                   |
| `0x04` | `DATA_START`               | sender -> client | actual offset and length, then raw bytes            |
| `0x05` | `CHUNK_CHECKSUM_REQUEST`   | client -> sender | first chunk and count                               |
| `0x06` | `CHUNK_CHECKSUMS`          | sender -> client | CRC32C list                                         |
| `0x07` | `COMPLETE`                 | sender -> client | bytes sent                                          |
| `0x7f` | `ERROR`                    | either           | error code and message                              |

`FileSender` authenticates `HELLO` before revealing the manifest, supports multiple
clients, uses `FileChannel.transferTo`, sends 8 MiB slices, and closes idle sockets
using the `TransferConfig` watchdog. `FileReceiver` opens parallel connections,
persists `.part` and `.resume`, retries network failures, resumes ranges, verifies
whole-file SHA-256, and requests CRC32C lists to repair corrupt chunks.

### Protocol gaps to verify or harden

- `PeerClient.requestRange()` checks the returned offset but not returned length.
- `PeerClient.readComplete()` reads `bytesSent` without comparing it to expected bytes.
- Range arithmetic in `FileSender` should be reviewed for overflow and malformed
  payload lengths.
- A repeated zero result from `FileChannel.transferTo()` could make a non-empty send
  loop indefinitely.
- Raw payload shape validation is limited in some range/checksum handlers.
- There is no protocol-level encryption, peer certificate, transfer expiry, or
  explicit cancellation frame. Cancellation is implemented by closing sockets.

## 8. UI architecture and browser limitations

### Current browser path

```text
Browser on Device A
  -> Next.js same-origin /api/* rewrite
  -> Java Backend A on localhost:7000 (default) or configured BACKEND_URL
  -> LAN control and PeerLink sockets from Backend A/B
  -> Peer
```

`NearbyDevices.tsx` polls `/api/lan/devices` every 3 seconds, posts manual
`/api/lan/connect`, uploads selected files to `/api/upload`, then posts `/api/lan/send`.
`IncomingOffers.tsx` polls `/api/lan/offers` every 3 seconds and posts accept/reject.
`TransfersPanel.tsx` polls `/api/transfers` every 1.5 seconds and posts actions.
`FileUpload.tsx` uses browser drag/drop and `react-dropzone`; folder sending in
Nearby uses the browser's `webkitdirectory` input and sends `X-Relative-Path`.

No UI file constructs a WebSocket. The backend has WebSocket infrastructure, but the
current UI does not use it. Progress displayed in the UI comes from polling the
`TransferManager` snapshots. `api.ts` contains localStorage session helpers for the
cloud/account API, but the Nearby legacy routes use direct Axios calls and do not
attach an account token.

The browser does not perform UDP, mDNS, raw TCP PeerLink, or direct peer HTTP. It is
not the LAN networking actor. It assumes the Java backend is available through the
Next rewrite, normally on localhost. That is the correct direction for a desktop
agent architecture: retain the browser as an unprivileged UI and put discovery,
inbound listeners, pairing, and file I/O in a local agent.

Browser limitations that make direct browser networking unsuitable include UDP and
mDNS unavailability, raw TCP restrictions, CORS, mixed-content rules, secure-context
requirements for newer local-network permissions, firewall prompts, and browser
lifecycle limits for background transfers. WebSocket to a LAN address is possible in
some deployments but would still not solve discovery, inbound file serving, native
filesystem access, or consistent browser permission behavior.

## 9. Backend-on-both-devices matrix

| Feature | Local backend on sender? | Local backend on receiver? | Reason |
|---|---:|---:|---|
| mDNS discovery | Yes | Yes | Each node advertises and browses its own JmDNS service |
| Heartbeat | Yes | Yes | The sender-side presence manager calls receiver `/lan/ping` |
| Nearby device list | Yes | No for viewing sender's list; yes for two-way discovery | UI reads the local registry; each node must run its own discovery to appear reciprocally |
| QR/manual pairing | Yes | Yes | Pairing routes and registry writes live in each Java node |
| LAN offer control | Yes | Yes | Sender posts `/lan/offer`; receiver hosts offer registry and approval endpoint |
| LAN file transfer | Yes | Yes | Sender hosts `FileSender`; receiver runs `FileReceiver` |
| Incoming offer | No for sender's UI; yes for receiving device | Yes | Receiver backend receives and stores offer, then exposes it to UI |
| Transfer progress | Yes for outgoing status | Yes for receive progress | `TransferManager` is local; current UI polls each local backend |
| Cloud upload | The uploading backend or cloud API | No | Storage-backed modes do not require a second local peer |
| Cloud download | No | The downloading client/backend as configured | Cloud/link path is a separate storage/API flow |

For the current Nearby implementation, “backend on both devices” is a literal
requirement, not merely a deployment convenience. The target agent replaces the local
backend role; the cloud backend should not be inserted into the LAN data path.

## 10. Persistence and cloud boundary

The Java code already has a useful conceptual split:

- Cloud-shaped services: `AuthService`, `JwtService`, `SessionService`, user services,
  username sharing, link sharing, plan enforcement, history, `StorageProvider`, and
  PostgreSQL/S3/MinIO seams.
- Device-shaped services: identity, registry, mDNS, heartbeat, pairing, offers,
  FileSharer, FileSender/Receiver, and the transfer queue.

The migrations contain `devices` and `trusted_devices`, but runtime discovery and trust
use `identity.properties` and `devices.properties`; there is no runtime PostgreSQL
repository for this state. `TransferManager` queue state and pending offers are also
in memory, while receiver partial data is local disk. A restart therefore loses active
offers and queue state, although known identity/trust records can survive if the data
directory survives.

The cloud can own user accounts, authenticated device registration, device metadata,
cloud sharing, stored files, transfer history, signaling/rendezvous, and fallback
policy. It should not own Nearby file bytes when a direct local path is available.

## 11. Target architecture mapped from this code

```text
                         FYLO
                           |
             +-------------+-------------+
             |                           |
       CLOUD BACKEND                LOCAL FYLO AGENT
             |                           |
   auth, users, metadata,       mDNS/DNS-SD discovery
   device account binding,      pairing and trust keys
   cloud links/storage,         local control HTTP/IPC
   signaling, history,          PeerLink listeners
   fallback transfer            FileSender/FileReceiver
                                 local queue and disk
             ^                           ^
             |                           |
             +------ Next.js UI ---------+
                 cloud API + localhost agent API
```

### Class disposition

| Existing area/class | Disposition | Reason |
|---|---|---|
| `DeviceDiscoveryService`, `DevicePresenceManager`, `HeartbeatService` | MOVE TO LOCAL AGENT | They need LAN interfaces and local peer reachability |
| `DeviceIdentity`, `DeviceRegistry` | SHARED / SPLIT | Agent owns key and live endpoint; cloud stores authenticated account/device metadata |
| `QrPairingService`, `TrustedDeviceService` | SHARED / SPLIT | Agent performs local proof and policy; cloud associates devices and user authorization |
| `LanMessages`, `ControlPlaneClient` | MOVE TO LOCAL AGENT, shared contract | Peer control remains local; add authenticated/versioned contract |
| `LanShareService`, `OfferManager` | MOVE TO LOCAL AGENT | Offers and consent are local-device actions |
| `PeerLinkProtocol`, `TransferManifest` | SHARED / LOCAL AGENT implementation | Preserve as versioned cross-platform protocol contract |
| `PeerClient`, `FileSender`, `FileReceiver`, `FileSharer`, `TransferManager` | MOVE TO LOCAL AGENT | They require local file handles, listeners, disk, and direct sockets |
| `TransferEngine` | SHARED / SPLIT | Keep common transfer abstractions; local agent owns Nearby execution, cloud backend owns stored/cloud execution |
| `WebSocketServer`, `WebSocketNotifier` | SPLIT | Agent exposes local progress/events; cloud keeps authenticated account notifications; current UI can initially continue polling |
| `AuthService`, `JwtService`, `SessionService`, user services | KEEP IN CLOUD | Account and multi-device control-plane responsibility |
| `UsernameShareService`, `LinkShareService`, plans, history | KEEP IN CLOUD | Cloud identity, policy, metadata, and durable storage responsibilities |
| `StorageProvider`, S3/MinIO/Postgres repositories | KEEP IN CLOUD | Durable cloud fallback and metadata; do not put cloud infrastructure in the agent |
| `FileSafetyService`, `RateLimiter` | SHARED / SPLIT | Equivalent input and rate policies are needed in both trust boundaries |
| `ApiRouter` | SPLIT | Cloud API remains; agent gets a narrow local API rather than the full account router |
| `FileController` | SPLIT, then reduce | It currently owns both worlds; first extract agent-facing modules, then remove cloud services from the agent |
| `App` | MOVE/REPLACE in agent packaging | Agent entry point should manage lifecycle, ports, data directory, and firewall integration |
| Database migrations | KEEP IN CLOUD, extend carefully | Cloud device/account metadata belongs here; local registry should remain resilient offline |
| Existing docs | UPDATE after contract decision | Current `_air`/`_peerlink` and port claims conflict |

## 12. Minimum-change migration path

### Phase 0: Baseline current behavior

**Files/classes:** no product change; preserve `LanModeIntegrationTest` and
`TransferIntegrationTest`.

**Checks:** run the focused tests, run two isolated nodes, record API, WebSocket, mDNS,
and dynamic FileSender ports, and test a small file. Confirm the default port contract
for local development and container startup.

**Risk:** current tests seed discovery and do not prove multicast or firewall behavior.
**Success:** current Nearby flow remains green and its actual ports are documented.

### Phase 1: Define and harden a local-agent boundary

**Files/classes:** extract interfaces around the existing `device`, `lan`, `transfer`,
`protocol`, and `service` packages; do not change the binary protocol yet.

**APIs:** local agent endpoints for health, device list, offers, offer actions,
send initiation, transfer snapshots, and transfer actions.

**Risk:** accidentally coupling the new boundary to cloud auth or browser file bytes.
**Success:** agent-side tests run without `AuthService`, `StorageProvider`, PostgreSQL,
or cloud configuration.

### Phase 2: Package Java as the desktop local agent

**Files/classes:** create an agent launcher/configuration around existing
`FileController` responsibilities; make API port, WebSocket port, dynamic data ports,
data directory, interface selection, and allowed origins explicit.

**APIs:** bind a localhost-only agent UI API first, with a separately authenticated
local session or origin-bound capability.

**Risk:** dynamic ports and inbound firewall rules are easy to miss.
**Tests:** startup/shutdown, port allocation, interface selection, mDNS registration,
manual connect, offer/accept/reject, restart, and firewall documentation.

### Phase 3: Move the Next.js Nearby surface to the agent API

**Files:** `ui/src/components/NearbyDevices.tsx`, `IncomingOffers.tsx`,
`TransfersPanel.tsx`, `ui/src/lib/api.ts`, and `ui/next.config.js` or a runtime agent
URL configuration.

**Behavior:** the browser talks to `http://127.0.0.1:<agent-port>` or a same-origin
desktop shell bridge. It never discovers peers or handles raw peer sockets.

**Risk:** CORS, mixed-content, Local Network Access prompts, and multiple UI tabs.
**Success:** the current UI can send, approve, resume, and cancel through the agent
while cloud login continues through the cloud API.

### Phase 4: Add cryptographic device identity and authenticated local control

**Files/classes:** evolve `DeviceIdentity`, `QrPairingService`, `TrustedDeviceService`,
`LanMessages`, `ControlPlaneClient`, and `PeerLinkProtocol` without breaking v1
interoperability during migration.

**Behavior:** pairing establishes a keypair and peer binding; discovery carries only
advisory data; control messages use authenticated encryption or mutually authenticated
TLS; tokens are scoped, expiring, and bound to the paired peer/offer.

**Risk:** pairing migration and revocation semantics.
**Tests:** spoofed TXT, spoofed device ID, replayed offer, expired token, revoked peer,
wrong peer key, and untrusted incoming control request.

### Phase 5: Connect agent state to the cloud control plane

**Files/classes:** cloud device metadata endpoints/repositories and a new agent cloud
connector; map local device public key to account-owned device row.

**Behavior:** cloud handles account association, user-visible device management,
signaling, and online metadata. Agent remains operational for local transfer when
offline from cloud.

**Risk:** cloud outage must not break LAN transfer; avoid making cloud presence a
precondition for mDNS or local offers.

### Phase 6: Add fallback selection, then desktop packaging

**Behavior:** attempt direct LAN only when a paired, reachable agent path exists;
otherwise use cloud/invite storage path. Add explicit reason codes and user-visible
path selection.

**Packaging:** auto-start, signed installers, firewall prompts, update strategy, and
per-platform data directories.

**Success:** desktop local agent works with no cloud, cloud fallback works with no
peer agent, and the browser UI does not need privileged network access.

## 13. Desktop-agent technology choice

### Option A: Keep Java as the local agent

**Recommendation: choose this first.** The existing LAN and transfer implementation
already supplies JmDNS, HTTP control, the PeerLink protocol, resumable transfer,
checksums, queueing, and tests. Java 25 runs on Windows, macOS, and Linux, and the
smallest migration is packaging and boundary extraction rather than a protocol rewrite.

Costs are installer size, JRE distribution, native auto-start integration, and a less
natural route to mobile. Those costs are real but are lower than reimplementing the
working data plane.

### Option B: Go

Go would produce simple single-binary desktop agents and strong cross-compilation.
It would require reimplementing JmDNS/DNS-SD integration, the HTTP control plane,
binary framing, file resume/checksum logic, pairing, and test parity. It becomes more
attractive if agent footprint and operational simplicity outweigh Java reuse.

### Option C: Rust/Tauri

Tauri is attractive for a combined desktop UI and strong memory-safety story, and it
can integrate platform APIs well. It still requires a Rust implementation or FFI
boundary for the Java protocol and transfer engine. It adds a larger rewrite and
desktop-shell decision while the current browser UI already works.

### Option D: Electron or a browser-only solution

Electron could package the UI and a local process, but it does not remove the need for
a privileged agent and has higher runtime footprint. Browser-only networking cannot
provide reliable UDP/mDNS, inbound listeners, native file access, or cross-platform
background behavior.

### Platform comparison

| Concern | Java agent | Go agent | Rust/Tauri |
|---|---|---|---|
| Reuse of current code | Excellent | Low | Low |
| Windows/macOS/Linux | Good with bundled JRE | Excellent single binary | Good, more packaging work |
| mDNS and TCP | Already implemented | Mature libraries, new code | Mature libraries, new code |
| Auto-start/installers | Separate platform packaging | Separate platform packaging | Tauri helps shell integration |
| Localhost UI API | Existing model | New model | Native bridge or localhost |
| Android/iOS reuse | Conceptual protocol only | Conceptual protocol only | Conceptual protocol only |
| Security memory model | Managed runtime | Strong practical baseline | Strongest low-level guarantees |
| Near-term effort | Lowest | Medium/high | High |

## 14. Mobile implications

### Desktop

Desktop can run a persistent local agent, bind inbound LAN TCP, advertise/browse DNS-SD,
and prompt for firewall access. The current Java logic is most reusable here.

### Android

Android can implement the same service type, JSON control contract, and PeerLink binary
protocol using NSD and platform sockets. It needs local-network permissions, foreground
service policy for long transfers, storage permission/scoped storage handling, battery
and background execution policy, and careful Wi-Fi/network-interface changes.
Persistent background listening is possible only within Android's service restrictions.

### iOS

iOS can use Bonjour service registration/browse and Network framework streams, but local
network permission prompts, background suspension, energy limits, and app lifecycle make
an always-listening agent unreliable. A transfer generally needs a foreground app or a
platform-supported background transfer design. An iOS app cannot be assumed to accept
inbound LAN connections indefinitely while suspended.

### Mobile-to-device feasibility

Mobile-to-desktop is realistic when the mobile app is foregrounded or runs an allowed
foreground service. Mobile-to-mobile is realistic for foreground sessions on the same
LAN. Background, unattended, guest-Wi-Fi, and AP-isolated cases need fallback through
cloud signaling/storage. WebRTC may help NAT traversal and encrypted sessions, but it
does not replace local discovery, consent, mobile lifecycle handling, or a cloud
signaling service. It should be evaluated only after the local agent contract exists.

## 15. Firewall and network requirements

An eventual desktop agent needs:

- inbound TCP to the agent API port for peer control and heartbeat;
- inbound TCP to each active dynamic `FileSender` port, or a redesigned single
  multiplexed data port;
- outbound TCP to peer API/data ports;
- mDNS multicast participation, normally UDP 5353 to `224.0.0.251` for IPv4, as
  provided by JmDNS;
- optional outbound HTTPS/WebSocket to the cloud control plane;
- interface selection when Ethernet, Wi-Fi, VPN, virtual adapters, or containers are
  simultaneously present.

The exact firewall rule set is platform-specific and should be tested rather than
assumed:

- Windows Firewall may need an inbound Java/agent rule and a dynamic data-port range.
- macOS may prompt for incoming connections and needs Local Network permission for
  Bonjour-like discovery.
- Linux needs firewall rules for TCP API/data ports and mDNS multicast; Avahi or
  another local network policy may affect multicast behavior.

Public Wi-Fi, guest Wi-Fi, AP/client isolation, and some VPNs can block peer-to-peer
traffic even when both devices have internet access. Multiple interfaces can cause
the current “first site-local IPv4” choice to advertise an unreachable address.
The current implementation does not prove runtime behavior for these cases.

Outbound-only connectivity is not sufficient for the current design because the
receiver must accept control and data connections. A future relay or WebRTC design
could reduce inbound requirements, but that is a different transport architecture.

## 16. Runtime verification status

### Performed

- Read-only repository inspection.
- `mvn -q -Dtest=LanModeIntegrationTest test`: passed.
- The test verified offer/accept/complete, direct binary transfer, SHA-256 result,
  nested folder creation, reject status, manual mutual connection, and trusted
  auto-accept.
- Test output showed JmDNS startup on both in-process nodes.

### Not performed or not possible from this workspace

`Runtime verification unavailable` for the requested two-physical-device workflow.
This environment provides one workspace and no second physical LAN host, so the
following remain UNKNOWN - requires runtime verification:

- discovery on the active physical LAN interface;
- actual multicast delivery between the user's two devices;
- OS firewall behavior and inbound dynamic ports;
- cross-subnet, guest-Wi-Fi, AP-isolation, VPN, and multiple-interface behavior;
- cloud fallback path on a disconnected LAN;
- exact packet path observed with a network capture;
- Docker Compose and Kubernetes deployment reachability as deployed.

The existing test is still valuable because it proves the local control and data path
without relying on a mock transfer. It explicitly does not prove mDNS discovery.

## 17. Dependency graphs

### Current local path

```text
Next.js browser UI
  -> Next.js rewrite (/api/lan, /api/upload, /api/transfers)
  -> FileController / LanHandler / upload and transfer handlers
  -> LanShareService + OfferManager + TransferManager
  -> ControlPlaneClient (JSON HTTP)              [control plane]
  -> FileSharer -> FileSender                    [sender]
  -> FileReceiver -> PeerClient                  [receiver]
  -> PeerLinkProtocol + TransferManifest         [binary protocol]
  -> TCP API/control + TCP dynamic file ports    [network]
  -> local disk
```

### Cloud responsibilities versus local-agent responsibilities

```text
Cloud backend
  -> AuthService/JWT/session/user services
  -> device account metadata and cloud signaling
  -> username sharing, links, plans, history
  -> PostgreSQL repositories
  -> StorageProvider (MinIO/S3/local cloud deployment)
  -> cloud WebSocket/user notifications
  -> cloud fallback transfer

Local Fylo Agent
  -> DeviceIdentity/keys and local pairing
  -> mDNS/DNS-SD and heartbeat
  -> local DeviceRegistry and trust cache
  -> LanMessages/ControlPlaneClient
  -> OfferManager/LanShareService
  -> FileSharer/FileSender/FileReceiver/TransferManager
  -> PeerLinkProtocol data sockets
  -> local staging/download directories
  -> localhost agent API for Next.js
```

## 18. Architectural problems ranked by severity

### CRITICAL: unauthenticated LAN control plane

**Evidence:** `FileController.LanHandler` accepts `/lan/hello`, `/lan/offer`,
`/lan/offer-result`, and offer actions without authentication or TLS; `ControlPlaneClient`
uses plain HTTP.

**Impact:** any host able to reach the API can inject offers, spoof device metadata,
change sender-side statuses, probe service behavior, and potentially cause receivers to
connect to attacker-selected transfer endpoints.

**Recommended fix:** establish cryptographic device keys during pairing; authenticate
and integrity-protect control messages; bind offers to a paired peer, endpoint, nonce,
expiry, and user consent. Treat mDNS only as discovery hints.

### CRITICAL: trust is keyed by spoofable UUID and weakly protected routes

**Evidence:** `DeviceIdentity` is an unprotected UUID; `QrPairingService.complete()`
trusts submitted identity fields; API pairing/trust routes do not call `require()`.

**Impact:** unauthorized trust or auto-accept can make future offers bypass consent.

**Recommended fix:** require authenticated local/cloud user context where appropriate,
use a public-key fingerprint as the identity, prove possession during pairing, and
persist directed trust edges with revocation.

### HIGH: dynamic data ports are a deployment blocker

**Evidence:** every `FileSender` binds its own generated port; Compose only exposes a
range and Kubernetes has no per-peer dynamic service; Docker and default ports already
disagree.

**Impact:** a packaged agent or container may discover a peer but fail to transfer
because the data port is not reachable.

**Recommended fix:** desktop agent should request firewall access for an explicit range,
or preferably expose one authenticated multiplexed data listener. Do not route Nearby
bytes through cloud merely to avoid fixing local port management.

### HIGH: cloud/local responsibilities are co-located

**Evidence:** `FileController` composes auth, storage, account services, mDNS, LAN
offers, and transfer engine in one process; UI rewrites every surface to one backend.

**Impact:** cloud deployment, scaling, and local-agent packaging cannot evolve
independently; running the cloud backend does not provide a usable local LAN agent.

**Recommended fix:** extract a narrow agent module/API around the existing local
classes, then change only Nearby UI calls to use it.

### HIGH: deployment manifests disagree

**Evidence:** Compose/Docker/OpenAPI/default app ports differ; Kubernetes deployment is
in namespace `air` with `app: air-backend`, while the Service is in `fylo` selecting
`app: fylo-backend`; Nginx targets `localhost:8080` and does not explicitly route the
separate WebSocket listener.

**Impact:** production startup and service discovery can fail before LAN behavior is
exercised.

**Recommended fix:** make one explicit port/config contract and add deployment smoke
tests for API, WebSocket, and dynamic data reachability.

### HIGH: no durable active-transfer or offer state

**Evidence:** `OfferManager` and `LanShareService` maps are in memory; `TransferManager`
queue state is in memory; only receiver partial/resume files survive.

**Impact:** restart loses pending consent and status callbacks, and users cannot
reliably recover the control state of a transfer.

**Recommended fix:** persist resumable local-agent job metadata separately from cloud
history; keep actual bytes and resume files local.

### MEDIUM: protocol validation and lifecycle gaps

**Evidence:** returned range lengths/completion byte counts are not fully checked;
zero-progress `transferTo` and payload-shape validation need review; cancellation is
socket-close only.

**Impact:** malformed or buggy peers can cause hangs, incorrect completion accounting,
or poor diagnostics.

**Recommended fix:** add strict protocol conformance tests and explicit cancellation,
timeouts, length checks, overflow checks, and progress guards.

### MEDIUM: current UI polling does not use existing WebSocket events

**Evidence:** three components poll while `WebSocketNotifier` exists; notifier
`broadcast()` sends progress/status to every authenticated connection.

**Impact:** unnecessary load and stale UI; switching directly to the current WebSocket
would risk cross-user progress leakage.

**Recommended fix:** first scope events to an authorized local agent/client or owner,
then adopt event streaming as an optimization rather than a prerequisite.

### MEDIUM: documentation and implementation drift

**Evidence:** mDNS service name, version field, default ports, TLS status, and backend
behavior differ across `LAN-MODE.md`, README, OpenAPI, Docker, and source.

**Impact:** future implementation work can target a protocol that is not actually
spoken.

**Recommended fix:** designate source plus versioned protocol contract as authoritative
and update docs after each boundary decision.

### LOW: single-interface IPv4 selection

**Evidence:** `pickSiteLocalAddress()` chooses the first qualifying interface/address;
`onResolved()` chooses the first IPv4 address.

**Impact:** VPNs, virtual adapters, and multi-homed machines can advertise an
unreachable address.

**Recommended fix:** advertise all eligible addresses or select the interface based on
the peer route; expose diagnostics in the agent API.

## 19. What is already good and should be preserved

- **One transfer engine:** Nearby and invite flows reuse `FileSharer`, `FileSender`,
  `FileReceiver`, `PeerClient`, and `TransferManager` rather than duplicating byte
  transfer logic.
- **Real direct data path:** the receiver pulls from the sender's local `FileSender`;
  the LAN control plane does not carry file bytes.
- **Clear protocol layer:** `PeerLinkProtocol` and `TransferManifest` isolate framing,
  versioning, ranges, errors, and metadata.
- **Auth-before-metadata for data:** `FileSender` checks the random token before sending
  the manifest.
- **Strong transfer resilience:** parallel ranges, exact resume offsets, retries,
  `.part`/`.resume` state, SHA-256 verification, and CRC32C repair are implemented and
  integration-tested.
- **Bounded local queue:** `TransferManager` limits active receives to three and has
  pause/resume/cancel behavior.
- **Consent model:** untrusted incoming offers require explicit approval, while a
  trusted-device policy supports a deliberate convenience path.
- **Path handling:** filenames and offered relative-path segments are sanitized before
  writing; folder preservation is tested.
- **Manual fallback:** `/lan/connect` provides a practical escape hatch when multicast
  is blocked.
- **Platform-neutral core shape:** the control JSON and binary protocol are not tied to
  the browser, so a native desktop or mobile agent can implement them later.
- **Optional infrastructure seams:** cloud storage and repository interfaces already
  separate durable cloud concerns from the local file transfer primitives.

## 20. Final executive summary

### Current Architecture

Fylo is one Java process per device. Each process hosts the browser gateway, local
identity and registry, JmDNS discovery, heartbeat, LAN offer control, dynamic file
senders, receiver queue, and cloud/account/storage services. The Next.js browser talks
to its own backend through same-origin rewrites. Nearby control uses direct HTTP between
the two backends; Nearby file bytes use direct TCP PeerLink connections from receiver
backend to sender backend. The cloud is not on the current Nearby byte path.

### Biggest Problem

The local device boundary does not exist yet, and the LAN control/trust plane is not
cryptographically authenticated. This makes the current all-in-one backend difficult
to package as a local agent and unsafe to expose as a production LAN control service.

### Recommended Architecture

Keep Java as the first local agent and extract the existing `p2p.device`, `p2p.lan`,
`p2p.protocol`, and `p2p.transfer` responsibilities behind a narrow localhost agent
API. Keep account authentication, metadata, cloud sharing, cloud storage, signaling,
and fallback in the cloud backend. Have the UI use both APIs: cloud for account/cloud
workflows and localhost agent for discovery, pairing, Nearby offers, local transfer,
and progress. Add key-based pairing and authenticated control before production LAN
deployment.

### What We Should Do NEXT

1. Freeze and test the current contract with two isolated backends: record the actual
   API, mDNS, WebSocket, and dynamic data ports, and add a physical two-device test
   plan without changing the transfer protocol.
2. Resolve the single port/configuration contract across `App`, Next.js, Docker,
   Compose, Kubernetes, Nginx, and OpenAPI; verify API, WebSocket, and data-port
   reachability.
3. Extract a narrow local-agent module/API around the existing discovery, pairing,
   offers, and transfer classes while keeping `LanModeIntegrationTest` green.
4. Point only the Nearby UI and transfer panels at the local agent API; keep cloud
   authentication and cloud-sharing UI on the cloud API.
5. Replace UUID-only trust with a persisted device keypair and proof-of-possession
   pairing; require authentication for trust management and pairing routes.
6. Authenticate and encrypt LAN control messages, add expiry/replay protection, and
   bind offer tokens to the paired peer and transfer.
7. Choose an explicit dynamic-port strategy: a documented desktop firewall range for
   the first agent, followed by evaluation of a single multiplexed data listener.
8. Add restart, malformed-peer, spoofing, revocation, firewall, multi-interface, and
   physical two-device tests before enabling automatic trusted-device acceptance.

### Do Not Do Yet

- Do not rewrite the working transfer engine in Go or Rust before the agent boundary
  and security contract are tested.
- Do not route direct LAN bytes through the cloud merely to hide dynamic-port issues.
- Do not implement Android or iOS agents before the desktop/local-agent contract is
  stable.
- Do not replace mDNS with WebRTC or cloud signaling as a first step; first preserve
  the existing local path and add fallback only when direct reachability fails.
- Do not scale Kubernetes replicas or tune cloud deployment before fixing the current
  port, namespace, selector, and WebSocket publication mismatches.
- Do not make cloud availability a prerequisite for local discovery or local transfer.
- Do not adopt the current broadcast WebSocket behavior for UI progress until event
  ownership and per-user/per-agent isolation are corrected.