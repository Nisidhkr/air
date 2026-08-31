---
name: air
description: >
  Build, run, test, and extend the Air unified file-sharing platform
  (this repo). Use when building the backend, running the server, executing
  or writing tests (unit / Testcontainers / Gatling), smoke-testing API
  endpoints, applying DB migrations, or adding features to any sharing mode
  (Direct, Nearby, Username, Link). Contains the environment quirks (WSL
  toolchain, Docker credential workaround, Testcontainers/Docker-29 pin)
  that are NOT discoverable from the code.
---

# Air — build, run, verify

Java 25 unified file-sharing backend (4 modes, ONE engine/API/auth/storage/DB).
Evolved from PeerLink; package root is `p2p.*` (`com.air` rename deliberately
deferred). Branch: `air-unified-platform`.

## Environment (critical — the toolchain lives in WSL, not Windows)

The repo is at `~/nk/PeerLink` **inside WSL Ubuntu**. Windows sees it via
`\\wsl.localhost\Ubuntu\...` (fine for file edits), but **all commands must
run in WSL**: `wsl -d Ubuntu bash <script>`. Windows git refuses the UNC path
("dubious ownership") — run git inside WSL too.

Every shell needs:

```bash
export JAVA_HOME="$HOME/.jdk/jdk-25.0.2"   # system java is 21 → build fails
export PATH="$JAVA_HOME/bin:$PATH"
cd ~/nk/PeerLink
```

Without JAVA_HOME, Maven dies with `release version 25 not supported`.

From Windows, don't inline complex commands (PowerShell/Git Bash mangle
`$(...)`, `$VAR`, and `/mnt/c` paths) — write a `.sh` script to the scratchpad
and run `wsl -d Ubuntu bash /mnt/c/<script-path>`.

## Build & test

```bash
mvn clean verify        # full build; 46 tests incl. Testcontainers
mvn -q test -Dtest=PlanEnforcementTest   # one class
mvn gatling:test -Dgatling.simulationClass=p2p.load.FyloLoadSimulation  # load tests (server must be running)
```

Testcontainers (PostgresIntegrationTest, MinioIntegrationTest, tagged
`integration`) need these exports — the machine's Docker CLI config points at
a missing `docker-credential-desktop.exe`, and the daemon is Docker 29
(min API 1.44, needs testcontainers ≥ 1.21.4, already pinned in pom):

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
export DOCKER_CONFIG=$(mktemp -d); echo '{}' > "$DOCKER_CONFIG/config.json"
export DOCKER_HOST=unix:///var/run/docker.sock
```

Skip them when Docker is unavailable: `-DexcludedGroups=integration`.
Same `DOCKER_CONFIG` trick is required for manual `docker pull/run`.

Debugging silent test infra: `slf4j-nop` swallows all logs. To see
Testcontainers diagnostics, build the test classpath
(`mvn dependency:build-classpath -Dmdep.includeScope=test`), strip the
slf4j-nop jar from it, and run a probe class — see git history of the
Docker-29 fix for the pattern.

## Run the server

```bash
mvn -DskipTests package
java -jar target/p2p-1.0-SNAPSHOT.jar [port]     # default 9090; WS on port+1
```

**For testing, always isolate state** (otherwise you pollute `~/.peerlink`):

```bash
DATA=$(mktemp -d)
java -Dpeerlink.data.dir="$DATA/d" -Dpeerlink.downloads.dir="$DATA/dl" \
     -jar target/p2p-1.0-SNAPSHOT.jar 9493 &
```

Ports 9090 (and often a stale instance on it) may be busy — pick 94xx for
tests. If an endpoint behaves like an old build, the running jar predates
your change: rebuild and restart.

Optional env (defaults = zero-infra single node): `FYLO_STORAGE`
(local|minio|s3 + `S3_*` vars), `DATABASE_URL`/`DATABASE_USER`/`DATABASE_PASSWORD`
(else JSON files in the data dir), `REDIS_URL` (else in-memory; a local Redis
on 6379 gets auto-detected). Full table in README "What's Running".
`docker compose up --build` boots the full stack (Postgres+MinIO+Redis-ready+
Prometheus+Grafana); K8s manifests in `k8s/` (`kubectl apply -k k8s/`).

## Smoke-test flows (curl, against $B=http://localhost:<port>)

```bash
# Auth (register returns 201 + tokens; refresh tokens are single-use)
curl -s -X POST $B/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"me","password":"secret123","email":"me@x.com"}'
TOKEN=$(curl -s -X POST $B/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"me","password":"secret123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')

curl -s $B/api/v1/plan -H "Authorization: Bearer $TOKEN"        # tier + usage
curl -s $B/health                                               # components UP
curl -s $B/metrics | grep fylo_                                 # Prometheus

# Mode 1 rendezvous: initiate → 6-char code → join
curl -s -X POST $B/api/v1/transfers/direct/initiate -H 'Content-Type: application/json' \
  -d '{"fileName":"f.txt","fileSizeBytes":1024}'                # → sessionCode
# Mode 1 bytes: POST /upload (multipart) → {port,token,code} → GET /download/{port}?token=…

# Mode 4: link upload → public download
SLUG=$(curl -s -X POST $B/api/v1/links/upload -H "Authorization: Bearer $TOKEN" \
  -F file=@/tmp/f.txt | python3 -c 'import sys,json;print(json.load(sys.stdin)["slug"])')
curl -s $B/s/$SLUG
```

Plan-limit checks: `?password=x` / `?expiryDays=30` / uploading a `.exe` on a
FREE account must return 403 `{"code":"plan_limit","limit":...}`.
**Premium in tests**: write `subscriptions.json` into the data dir
(`[{"userId":"<uuid>","storageBytes":536870912000,"expiresAtEpochMs":0}]`)
and restart, or call `PlanService.grantPremium` in Java tests.
Rate limiter: ~11th bad login within a minute → 429.
WebSocket: `GET /ws/events?token=…` on the API port → 426 with the real
`ws://host:port+1/ws/events` URL (401 without token).

Full reference smoke scripts exist in the session scratchpad pattern; the
API contract is `docs/openapi.yaml` (source of truth).

## Architecture rules (enforced by review, not the compiler)

- **One engine**: modes never touch `FileSender`/`FileReceiver`/storage
  directly — everything goes through `p2p.engine.TransferEngine`.
- **Plan enforcement lives in services** (`LinkShareService.enforceCreate`,
  `PlanService`), never in `ApiRouter`; API just maps `PlanLimitException`
  → 403 `plan_limit`.
- **Repository seams**: `UserRepository`, `TransferSessionRepository`,
  `TransferRequestRepository`, `TransferHistoryRepository`, `StorageProvider`
  each have a JSON-file impl (default) and a Pg/S3 impl (env-selected).
  New persistent state follows the same pattern (see `PersistedJson`).
- **Redis is optional**: anything using `p2p.infra.RedisClient` must keep an
  in-memory fallback and stay correct when `isAvailable()` is false.
- Guest modes (Direct, Nearby) must never require auth, plans, or storage.
- `FileController` is the composition root (init order documented inline).
- User IDs are UUID strings everywhere (history/persistence parse them).

## Database

Migrations: `db/migrations/V1..V3` (plain SQL, Flyway-named). CI applies them
to blank Postgres 16; validate locally with a throwaway container:
`docker run -d -p 55432:5432 -e POSTGRES_PASSWORD=v postgres:16-alpine`, then
`psql -v ON_ERROR_STOP=1 -f` each file in order. Schema uses backbone column
names (`short_code`, `sender_id`, `status` …) — keep Java Pg repos in sync.

## Docs map

`docs/UNIFIED-PLATFORM.md` (Part 1 architecture + migration),
`docs/UNIFIED-PLATFORM-PART2.md` (ER, security, plans, scaling),
`docs/openapi.yaml` (API), `FYLO_PROJECT_BACKBONE.md` (original spec —
where it disagrees with code, the code + openapi.yaml win),
`deploy/` (Prometheus/Grafana/alerts), `k8s/` (production manifests).
