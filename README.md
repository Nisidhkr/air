# Air - P2P File Sharing Application

Air is a peer-to-peer file sharing application for files of any size
(tested with multi-GB transfers, designed for 40 GB+). A sender uploads a
file and gets an invite code; anyone with the code downloads the file
directly from the sender's machine over a custom binary protocol with
authentication, resume, and integrity verification.

## Features

**Two transfer modes, one engine** — internet sharing via invite codes, and
zero-configuration local network sharing ([docs/LAN-MODE.md](docs/LAN-MODE.md)):

- **Nearby devices** appear automatically via mDNS/DNS-SD (Bonjour-compatible);
  select a device, send files or whole folders — no upload to any server, no
  IP/port entry, no internet required
- **Device approval**: "X wants to send 5 files (12.4 GB) — Accept / Reject",
  with optional "always accept" pairing (trusted devices)
- **Transfer queue** with progress, speed, ETA, pause/resume/cancel, and history
- Online/offline presence with heartbeats and last-seen tracking

Plus the original internet mode:

- Drag-and-drop upload with progress, streamed to disk (constant memory — no
  file-size limit from RAM)
- One-string invite codes (`port-token`) with per-transfer authentication
- Custom binary transfer protocol ([docs/PROTOCOL.md](docs/PROTOCOL.md)):
  version, manifest (name, size, SHA-256, chunk size, transfer ID), byte-range
  requests, completion marker
- Zero-copy sending (`FileChannel.transferTo` / `sendfile`) and parallel
  segmented downloading
- Exact byte-offset resume after disconnects — already-received bytes are
  never re-downloaded (browser downloads resume via HTTP `Range` too)
- SHA-256 whole-file verification with per-chunk CRC32C repair: corrupted
  chunks are located and re-fetched instead of failing the transfer
- Multiple clients can download the same share concurrently
- Minimal, responsive UI

## Project Structure

- `src/main/java/p2p`: Java backend (the unified Fylo platform — see
  [docs/UNIFIED-PLATFORM.md](docs/UNIFIED-PLATFORM.md))
  - `App.java` — entry point (one server, one JVM)
  - `controller/FileController.java` — HTTP gateway (`/upload`, `/download`,
    `/lan/*`, `/transfers`) and composition root
  - `api/ApiRouter.java` — unified API (`/api/v1/**`: auth, users,
    notifications, transfer requests, links, devices, pairing; `/s/{slug}`
    public link downloads)
  - `engine/` — the ONE transfer engine facade (`TransferEngine`,
    `TransferSource`, `ShareCodes`)
  - `service/FileSharer.java` — registry of active shares
  - `protocol/` — binary wire protocol (frames, manifest, errors)
  - `transfer/` — `FileSender`, `FileReceiver`, `PeerClient`,
    `TransferManager` (queue/pause/resume), resume state, progress tracking,
    network tuning presets
  - `share/` — mode services: Direct (codes/QR), Username (request/accept),
    Link (cloud links, 2-day default expiry); Nearby lives in `lan/`
  - `auth/` — JWT (HS256), PBKDF2 password hashing, refresh-token rotation
  - `user/` — accounts, @username search, presence, notifications, history
  - `storage/` — `StorageProvider` seam + local implementation (S3/MinIO later)
  - `device/` — identity, registry, mDNS discovery, presence/heartbeats,
    QR pairing, trusted-device policy
  - `lan/` — offers, approval, device-to-device control plane
  - `security/` — transfer tokens
- `ui/`: Next.js frontend (`src/app`, `src/components`)
- `db/migrations/`: PostgreSQL schema (the ONE database; applied by compose
  and CI)
- `deploy/`: Prometheus/Grafana provisioning for the compose stack
- `docs/`: [UNIFIED-PLATFORM.md](docs/UNIFIED-PLATFORM.md) (Part 1
  architecture, diagrams, migration plan),
  [UNIFIED-PLATFORM-PART2.md](docs/UNIFIED-PLATFORM-PART2.md) (storage, DB,
  security, plans, scaling, observability), [openapi.yaml](docs/openapi.yaml)
  (OpenAPI 3.1 / Swagger spec), [PROTOCOL.md](docs/PROTOCOL.md) (wire format),
  [ARCHITECTURE.md](docs/ARCHITECTURE.md) (design, performance, security)

## Prerequisites

- **Java 25** (build target; installed by the upgrade tooling at `~/.jdk/jdk-25.0.2` —
  make sure `JAVA_HOME` points there, see below)
- Node.js 18+ and npm
- Maven

## Getting Started

### Build and run (after pulling changes, run these to refresh everything)

Backend — terminal 1:

```bash
# One-time per shell (or add to ~/.zshrc): use the Java 25 JDK
export JAVA_HOME="$HOME/.jdk/jdk-25.0.2"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package
java -jar target/p2p-1.0-SNAPSHOT.jar
```

> Without `JAVA_HOME` set, Maven uses the system Java 21 and fails with
> `release version 25 not supported`.

The desktop Fylo Local Agent listens on `127.0.0.1:7000` by default. It owns
LAN discovery, pairing, direct PeerLink transfers, and the browser-facing
`/agent/*` API. The Docker deployment runs the unified backend explicitly on
port `9090`.

```bash
FYLO_AGENT_PORT=7001 java -jar target/p2p-1.0-SNAPSHOT.jar
FYLO_AGENT_URL=http://127.0.0.1:7001 npm run dev         # from ui/
```

Frontend — terminal 2:

```bash
cd ui
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000).

Tests only:

```bash
mvn test
```

### Quick start scripts

`./start.sh` (Linux/macOS) or `start.bat` (Windows) build the backend, start
the server, and launch the frontend dev server.

## How It Works

### Local network mode

1. Open PeerLink on two devices on the same network — they discover each
   other automatically (mDNS) and appear under **Nearby**.
2. Pick a device, choose files or a folder. The other side gets an approval
   popup; on accept, the bytes flow **directly between the two machines**
   over the binary protocol (parallel segments, resumable, SHA-256 verified)
   into `~/Downloads/PeerLink`. Progress lives in the **Transfers** tab.

### Internet mode

1. **Send** — drop a file in the UI. It streams to the backend, which starts
   a `FileSender` on a random high port with a fresh 128-bit access token and
   returns the invite code `port-token`.
2. **Share** — send the invite code to the recipient.
3. **Receive** — the recipient enters the code. Their browser streams the file
   straight to disk through the backend gateway, which speaks the binary
   protocol to the sender. Nothing is served — not even the filename — without
   the correct token.
4. **Resilience** — interrupted protocol downloads resume from the exact byte
   offset; completed files are verified against the manifest's SHA-256 before
   being accepted.

## Troubleshooting LAN mode

- **`ECONNREFUSED 127.0.0.1:7000` in the frontend log** — the local agent
  isn't running. Start it first (`java -jar target/p2p-1.0-SNAPSHOT.jar`),
  then the UI; the dev proxy just forwards `/api/*` to it.
- **Setup for two laptops** — run the *backend* on both (each backend is the
  "device"); run the frontend on whichever machine you're using. The frontend
  only ever talks to its own local backend.
- **Devices don't appear, and a machine runs WSL2** — WSL2's default NAT
  network (a virtual `172.x` subnet) blocks both mDNS multicast *and* inbound
  connections, so a WSL2 node can neither be discovered nor receive transfers.
  Fix one of these ways:
  1. Enable **mirrored networking** (Windows 11 22H2+): put
     `[wsl2]` / `networkingMode=mirrored` in `C:\Users\<you>\.wslconfig`, run
    `wsl --shutdown`, restart, and allow Java/port 7000 through Windows
     Firewall. WSL then shares the laptop's real LAN address.
  2. Or run the backend **on Windows directly** (the jar is portable; install
     a Windows JDK 21+).
- **Devices don't appear on a normal network** (guest Wi-Fi / AP isolation /
  corporate networks often filter multicast) — use **"Add device by IP"** at
  the bottom of the Nearby tab: enter the other machine's address (shown by
  `ip addr` / `ipconfig`), and both devices register each other in one step.

## Architecture

```
Browser ──HTTP──► FileController (gateway)
                    │ POST /upload      stream to disk → FileSharer.offer()
                    │ GET  /download    PeerClient → stream-through (Range support)
                    ▼
                  FileSharer ─── one FileSender per shared file
                                   N concurrent clients, zero-copy sendfile
Peer ◄──PeerLink binary protocol──┘
  FileReceiver: parallel segments, .resume metadata, SHA-256 + CRC32C repair
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full design review,
bottleneck analysis, memory budget, network tuning (LAN/WAN/high-latency
presets), and benchmark numbers.

## Security

- Per-transfer 128-bit bearer tokens (constant-time comparison); auth happens
  before any metadata is revealed
- Path traversal protection on filenames in both directions
- Range and checksum requests are bounds-checked
- Not yet included (recommended for hostile networks): TLS, token expiry,
  rate limiting — see the security section of
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## What's Running

One backend process serves everything:

| Surface | Where |
|---|---|
| Local Agent API | `http://127.0.0.1:7000` — `/agent/**` and local transfer routes |
| Unified backend API | `http://localhost:9090` in Docker — `/api/v1/**` per [docs/openapi.yaml](docs/openapi.yaml) |
| Gateway (legacy UI routes) | `/upload`, `/download/{port}`, `/lan/*`, `/transfers` |
| Public link downloads | `GET /s/{slug}` (Range supported) |
| WebSocket events | `ws://localhost:9091/ws/events?token=<accessToken>` (RFC 6455; `GET /ws/events` on 9090 answers 426 with this URL) |
| Health (K8s probes) | `GET /health` and `GET /actuator/health` |
| Prometheus metrics | `GET /metrics` |

Environment variables (all optional — defaults give a zero-infra single node):

| Variable | Values | Default |
|---|---|---|
| `FYLO_STORAGE` | `local` \| `minio` \| `s3` | `local` |
| `DATABASE_URL` | `jdbc:postgresql://…` | none → JSON files |
| `DATABASE_USER` / `DATABASE_PASSWORD` | string | `fylo` / empty |
| `REDIS_URL` | `redis://…` | none → in-memory |
| `S3_ENDPOINT` / `S3_REGION` / `S3_BUCKET` | url / region / name | none |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | string | none |
| `FYLO_AGENT_PORT` | number | `7000` (WebSocket = port+1) |
| `PORT` | number | backend deployment override; Docker uses `9090` |
| `FYLO_BLOCK_EXECUTABLES` | `true` \| `false` | `true` |

Quick start (plain Java, no Docker):

```bash
mvn package -DskipTests
java -jar target/p2p-1.0-SNAPSHOT.jar
```

Quick start (full stack — Postgres, MinIO, Redis-ready, Prometheus, Grafana):

```bash
docker compose up --build
```

Load tests (Gatling, against a running backend): `mvn gatling:test
-Dgatling.simulationClass=p2p.load.FyloLoadSimulation`. Kubernetes
manifests for production live in [k8s/](k8s/) (`kubectl apply -k k8s/`).

## Deployment

For detailed deployment instructions, see [DEPLOYMENT.md](DEPLOYMENT.md).
Note: deployment images must provide a Java 21 runtime.

## License

MIT
# air
