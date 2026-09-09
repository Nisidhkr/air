# Fylo Local Agent

The Fylo Local Agent is the headless Java process that runs on each desktop. It
keeps local networking and filesystem access out of the browser while reusing
Fylo's existing LAN and PeerLink implementation.

## Responsibilities

The agent owns local device identity, JmDNS discovery, presence and heartbeat,
pairing and trusted-device state, LAN HTTP control, PeerLink TCP connections,
transfer progress, and the local filesystem used by transfers.

The cloud/backend remains responsible for authentication, accounts, metadata,
object storage, cloud-only sharing, billing, and future cloud signaling. The
browser is a UI client of the local agent and does not perform mDNS or direct
peer discovery.

## Runtime shape

```text
Next.js UI :3000
      | localhost HTTP
      v
Fylo Local Agent
  127.0.0.1:7000  /agent/*
      | LAN control: /lan/*
      | PeerLink TCP: dynamic high port per shared file
      v
Other Fylo Local Agents
```

`p2p.agent.FyloLocalAgent` is the headless entry point. It wraps the existing
`FileController` composition root rather than creating a second transfer or
discovery engine. `p2p.App` remains a compatibility entry point.

## Lifecycle

Startup loads `AgentConfig`, loads or creates `DeviceIdentity`, initializes the
`DeviceRegistry`, starts the HTTP and WebSocket listeners, starts presence, and
starts JmDNS discovery/registration. Shutdown stops discovery and presence,
closes transfer and storage resources, closes WebSocket/HTTP listeners, and
shuts down executors.

The agent registers and browses the authoritative DNS-SD service type
`_fylo._tcp.local.`. Each service advertises the agent HTTP port and TXT
records `id`, `name`, `os`, `type`, `version`, and `capabilities`. A peer is
added to `DeviceRegistry` on resolution and marked offline on service removal or
missed heartbeats.

## Local API

The browser-facing API is loopback-only and is exposed at
`http://127.0.0.1:7000` by default:

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/agent/status` | lifecycle state, ports, version, limitations |
| GET | `/agent/device` | local device identity |
| GET | `/agent/devices` | nearby and recently known devices |
| GET | `/agent/offers` | pending incoming offers |
| POST | `/agent/pair` | start or complete pairing |
| POST | `/agent/connect` | manual multicast-free peer connection |
| POST | `/agent/send` | send uploaded local shares to a device |
| POST | `/agent/offers/{id}` | accept or reject an offer |
| GET | `/agent/transfers` | transfer snapshots and progress |
| POST | `/agent/transfers/{id}` | pause, resume, or cancel |
| POST | `/agent/transfers/{id}/cancel` | cancel a transfer |

The existing `/upload` and `/download/{port}` routes remain the local upload and
streaming surfaces used to create and consume shares. The UI reaches them via
Next.js rewrites to the same agent target. Device-to-device control continues
to use `/lan/*`; those routes are not browser discovery APIs.

## Transfer flow

The sender UI uploads files to the local agent. The existing `FileSharer` opens
a dynamic high TCP port and returns share metadata. `/agent/send` delegates to
`LanShareService`, which sends an offer over the LAN control plane. After the
receiver approves, `TransferManager` starts the existing `FileReceiver` and
`PeerClient` path directly against the sender's `FileSender`. File bytes never
pass through cloud services.

The PeerLink data path retains resumable ranges, SHA-256 verification, CRC32C
repair where implemented, bounded queues, path sanitization, cancellation, and
progress tracking.

## Port contract

- `7000`: default local agent HTTP/API port.
- `7001`: default agent WebSocket event port (`agentPort + 1`).
- `49152-65535`: dynamic PeerLink/FileSender ports. The share offer carries the
  selected port and token; these ports are not a second agent API.
- `9090` and `9091` are legacy cloud/backend deployment defaults, not the local
  agent contract. Prometheus, Docker, and Kubernetes manifests may continue to
  use those service ports for the unified backend deployment.

Override the agent port with `FYLO_AGENT_PORT`; use `FYLO_AGENT_HOST` only for
runtime binding metadata. The agent API itself remains loopback-guarded.

## Running locally

```bash
export JAVA_HOME="$HOME/.jdk/jdk-25.0.2"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -DskipTests package
java -Dpeerlink.data.dir="$PWD/.fylo-data" \
     -Dpeerlink.downloads.dir="$PWD/.fylo-downloads" \
     -jar target/p2p-1.0-SNAPSHOT.jar 7000
```

Start the UI from `ui/` with its default rewrite target, or set
`FYLO_AGENT_URL` when the agent uses another port.

## Two-device verification

Run one agent and UI on each computer on the same LAN. Confirm both devices
appear in Nearby, send a real file, and verify the received size and SHA-256.
Stop the second agent and wait for its device to become offline, then restart it
and confirm discovery returns. Repeat with a large file, cancellation, failed
transfers, duplicate discovery, and manual connect when multicast is blocked.
The automated `LanModeIntegrationTest` covers the real two-node HTTP/TCP flow
in one JVM; it seeds discovery because CI normally has no multicast.

## Security limitations and next phase

This phase preserves existing bearer tokens, approval, UUID identity, and trust
flags but does not provide authenticated LAN control, cryptographic device
identity, replay protection, or authenticated PeerLink encryption. LAN control
currently uses HTTP and PeerLink bytes are not encrypted. The next security
phase should add cryptographic identity, authenticated control messages, secure
pairing, replay protection, and authenticated PeerLink without changing the
agent boundary.

Desktop tray packaging is intentionally deferred. A future tray application
will only display agent status and delegate all networking and transfer work to
`FyloLocalAgent`.
