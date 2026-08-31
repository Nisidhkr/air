# Air LAN Mode — Design & Migration Notes

LAN mode adds zero-configuration, server-free sharing between devices on the
same network, **as a second transfer mode beside the existing internet/invite
flow**. Nothing in the web-sharing path was removed or redesigned; the new
mode is a control plane layered on the same engine.

## 1. The unifying idea

An internet share already produces everything a transfer needs: a running
`FileSender` on a port plus an access token (that pair *is* the invite code).
LAN mode does not introduce a second data path — it just delivers the
port+token to the receiving *device* automatically instead of to a *human*:

```
                         Unified architecture
                ┌────────────────────────────────────┐
 Internet mode  │ invite code "port-token" → human   │
                │   shares it out of band            │
                ├────────────────────────────────────┤
 LAN mode       │ OfferRequest {files:[{port,token}]}│
                │   POSTed to the peer over LAN      │
                └────────────────┬───────────────────┘
                                 ▼
                 Common transfer engine (p2p.transfer)
                 FileSender (zero-copy sendfile, multi-client)
                 FileReceiver (parallel segments, resume,
                 SHA-256 + CRC32C repair, retries)
                 PeerLink binary protocol (docs/PROTOCOL.md)
```

Chunking, resume, metadata, checksums, progress, the security token layer,
the transfer queue, and the wire protocol are therefore *shared by
construction* — there is exactly one implementation of each.

## 2. New components

| Package | Class | Role |
|---|---|---|
| `p2p.device` | `DeviceIdentity` | persistent device ID + name + OS (survives restarts) |
| | `DeviceRegistry` | known devices, online state, last-seen, trusted flags; persisted |
| | `DeviceDiscoveryService` | mDNS/DNS-SD registration + browsing (`_peerlink._tcp.local.`) |
| | `HeartbeatService` | end-to-end liveness probe (`GET /lan/ping`) |
| | `DevicePresenceManager` | online/offline policy (2 missed heartbeats → offline) |
| `p2p.lan` | `LanMessages` | control-plane JSON records (offer, result) |
| | `OfferManager` | receiver-side approval popup state, trusted auto-accept, path sanitization |
| | `LanShareService` | sender-side: shares → offer → outcome tracking |
| | `ControlPlaneClient` | device-to-device JSON POSTs |
| `p2p.transfer` | `TransferManager` | queue (3 concurrent), pause/resume/cancel, history, progress views |

Existing classes changed minimally: `FileSharer` exposes share metadata and
relative paths; `FileSender` advertises the original filename; `FileReceiver`
gained `abort()` (the primitive behind pause/cancel); `FileController` hosts
the new endpoints.

## 3. Discovery: why mDNS/DNS-SD

mDNS + DNS-SD is the one zeroconf protocol implemented natively on every
target: Bonjour (macOS, iOS, Windows via Bonjour service), NSD on Android,
Avahi on Linux. "mDNS", "Bonjour", "Zeroconf", and "DNS-SD" are the same
protocol family — choosing it covers all of them. The JVM side uses JmDNS
(pure Java, no native deps, works on Windows/Linux/macOS).

Each node advertises `_peerlink._tcp.local.` with its HTTP API port and TXT
records (`id`, `name`, `os`, `type`, `v`). Peers appear automatically — no
IPs, no ports, no configuration. Discovery is best-effort: where multicast is
blocked (guest Wi-Fi, some VPNs, WSL2 NAT in some setups), devices simply
don't auto-appear and the invite-code flow still works; an incoming offer also
registers the sender (the registry learns from real traffic, not only mDNS).

**Cross-platform strategy for mobile:** Android/iOS apps implement the same
two things desktop nodes do — (1) advertise/browse `_peerlink._tcp` with
NSD/`NWBrowser`, (2) speak the control plane (3 small JSON endpoints) and the
binary protocol (framed messages + raw ranges; see PROTOCOL.md). No part of
the protocol depends on the JVM.

## 4. LAN flow end to end

```
Sender UI            Sender node                Receiver node            Receiver UI
   │ pick files +       │                            │                       │
   │ pick device        │                            │                       │
   ├──POST /upload×N───►│ (localhost only;           │                       │
   │                    │  no internet upload)       │                       │
   ├──POST /lan/send───►│──POST /lan/offer──────────►│ register offer        │
   │                    │                            │   trusted? auto-accept│
   │                    │◄──POST /lan/offer-result───│ "accepted"            │
   │                    │                            ├──approval popup──────►│
   │                    │◄═══ binary protocol pulls ═╡ FileReceiver per file │
   │                    │    (parallel, resumable)   │ → ~/Downloads/PeerLink│
   │                    │◄──POST /lan/offer-result───│ "completed"/"failed"  │
```

- Folders: the browser uploads each file with its `webkitRelativePath` in an
  `X-Relative-Path` header; the receiver recreates the directory tree, with
  every path segment sanitized (no `..`, no absolute paths).
- Multiple files: one offer, N pulls, queued by `TransferManager`
  (one sender → many receivers and many senders → one receiver both work:
  `FileSender` is multi-client, and the queue accepts offers from any number
  of peers).
- Interrupted transfer: the receiver's `.resume` metadata continues from the
  exact byte offset — same machinery as internet mode.

## 5. Security model

| Layer | Internet mode | LAN mode |
|---|---|---|
| Data access | 128-bit per-share bearer token, constant-time check, auth before metadata | same (tokens ride inside offers) |
| Consent | receiver chose to download | **approval popup** per offer ("X wants to send 5 files (12.4 GB) — Accept/Reject") |
| Pairing | n/a | "Always accept from this device" → persisted trusted flag; auto-accept |
| Identity | n/a | persistent device UUID; sender address taken from the TCP connection, not the payload (no address spoofing) |
| Paths | filename sanitization | per-segment sanitization of offered relative paths |

**TLS status:** not yet enabled. The plan: control plane first (HTTPS with
per-device self-signed certs, trust-on-first-use at pairing time — the
approval popup is the natural TOFU moment), then optional data-plane TLS.
Note the engineering trade-off: TLS on the data path forces encryption in
user space, which forfeits `sendfile` zero-copy (expect roughly 2-3× more CPU
per byte; AES-NI keeps it line-rate on LAN for most hardware). That is why it
should stay a per-transfer option rather than always-on for trusted LANs.

## 6. API additions (existing endpoints unchanged)

| Endpoint | Purpose |
|---|---|
| `GET /lan/ping` | liveness + identity (used by heartbeats) |
| `GET /lan/devices` | self + discovered/recent devices |
| `POST /lan/send` | `{deviceId, ports[]}` — offer existing shares to a device |
| `POST /lan/offer` | device→device: incoming offer |
| `GET /lan/offers` | pending offers (approval UI) |
| `POST /lan/offers/{id}` | `{action: accept\|reject, trust?}` |
| `POST /lan/offer-result` | device→device: accepted/rejected/completed/failed |
| `GET /transfers` | queue + history with progress/speed/ETA |
| `POST /transfers/{id}` | `{action: pause\|resume\|cancel}` |

`POST /upload` response gained `name` and `size` fields (superset; `port` and
`token` are unchanged, so existing clients keep working).

## 7. Backward compatibility & migration

- **Phase 0 (done in the original refactor):** one transfer engine under the
  invite-code flow.
- **Phase 1 (this change):** LAN mode added strictly additively — new
  packages, new endpoints, four new UI components, two pom dependencies
  (JmDNS, Jackson). The invite-code path's behavior is covered by the
  pre-existing integration tests, which still pass unmodified.
- **Phase 2 (future):** TLS rollout per §5; transfer notifications via
  SSE/WebSocket instead of polling; mobile clients per §3; adaptive buffer
  sizing from observed throughput (today: fixed 1 MiB receive buffers +
  OS-tuned socket buffers, which LAN benchmarks show is not the bottleneck).

Memory stays within the large-file budget: LAN mode adds only control-plane
state (offers, device records — bytes, not buffers); a 40 GB LAN transfer
uses the same <10 MB engine footprint, bounded by the 3-concurrent-transfer
queue to ~30 MB worst case.
