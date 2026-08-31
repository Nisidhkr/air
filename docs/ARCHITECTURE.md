# Air Large-File Transfer Architecture

Review of the v0 implementation, the bottlenecks that made 40 GB transfers
impossible, and the design that replaced it. Protocol details live in
[PROTOCOL.md](PROTOCOL.md).

## 1. Review of the previous implementation

| Area | v0 behavior | Consequence at 40 GB |
|------|-------------|----------------------|
| Upload | `IOUtils.copy` into a `ByteArrayOutputStream`, then `new String(data)` for parsing | ≥ 80 GB of heap needed (byte[] + String); `OutOfMemoryError` at ~1–2 GB. Also `byte[]` is capped at 2 GB (`int` index) |
| Download | Whole file copied to a temp file, then re-read to the browser | 2× disk I/O, 2× latency, 40 GB of temp space |
| Wire format | `Filename: x\n` + raw bytes | No size, no checksum, no resume, no auth, no completion marker — a truncated transfer is indistinguishable from a complete one |
| Serving | `ServerSocket.accept()` once; thread-per-client via `new Thread` | Exactly one download per share, ever; unbounded threads |
| I/O | 4 KB buffer, `FileInputStream` → `OutputStream` | ~4 syscalls + 2 user-space copies per 4 KB; high CPU at high throughput |
| Failure handling | Catch-and-print | Any blip loses all progress; partial temp files leak |
| Security | None | Anyone who can reach the port gets the file; uploaded filenames not sanitized |

`int` vs `long`: v0's multipart parser indexes everything with `int` byte
offsets, hard-capping files at 2 GB. The new code paths use `long` for every
file offset/size; `int` only appears for buffer-sized quantities (≤ 1 MiB).

## 2. New design

```
Browser ──HTTP──► FileController (gateway, virtual threads)
                    │  POST /upload   → stream to disk → FileSharer.offer()
                    │  GET /download  → PeerClient → stream-through
                    ▼
                  FileSharer ── one FileSender per shared file
                                  │ N concurrent clients (virtual threads)
                                  │ FileChannel.transferTo (sendfile)
Peer CLI ──PeerLink protocol──► FileSender
   FileReceiver: N parallel segment connections, positioned writes,
   ResumeState (.resume), SHA-256 + per-chunk CRC32C verify/repair
```

Key classes: `p2p.protocol.PeerLinkProtocol` / `TransferManifest`,
`p2p.transfer.FileSender` / `FileReceiver` / `PeerClient` / `ResumeState` /
`ProgressTracker` / `TransferConfig`, `p2p.security.TransferTokens`.

## 3. Why each optimization helps

- **`FileChannel.transferTo` (sender)** — on Linux this is `sendfile(2)`:
  page cache → socket inside the kernel. A read/write loop moves every byte
  kernel→user→kernel (2 copies + 2 syscalls per buffer); for 40 GB that is
  ~80 GB of memcpy eliminated and ~20M syscalls reduced to ~5K (8 MiB slices).
  CPU drops from "one core saturated" to a few percent, which matters when
  several clients download simultaneously.
- **1 MiB direct buffers (receiver)** — direct buffers let the JVM hand the
  OS a stable native address (no copy to a temporary native buffer as with
  heap arrays), and 1 MiB amortizes syscall cost ~256× vs the old 4 KB.
- **Positioned writes (`FileChannel.write(buf, pos)`)** — `pwrite(2)` under
  the hood: parallel segments share one channel with no seek races and no
  locking in our code.
- **Sparse preallocation** — the part file is set to full length up front, so
  positioned writes never race file growth and disk-full fails in second 1,
  not hour 3.
- **Virtual threads (Java 21)** — blocking-style code, but an idle connection
  costs ~1 KB instead of a 1 MB platform-thread stack; thousands of concurrent
  transfers are fine. Used for sender connections, receiver segments, and the
  HTTP executor.
- **CRC32C for chunks** — hardware-accelerated (SSE4.2/ARMv8); ~10× cheaper
  than SHA-256 where only error *location* (not cryptographic strength) is
  needed. The whole-file SHA-256 remains the source of truth.
- **Background SHA-256 at offer time** — hashing 40 GB takes ~15 s on NVMe;
  doing it on a `CompletableFuture` keeps `offer()` instant, and only the
  first MANIFEST waits.

## 4. Parallel transfer strategy

Single TCP stream throughput is bounded by `window/RTT` and collapses on every
loss event. Segmented downloading splits the file into N contiguous ranges,
one connection each, all writing into the preallocated part file at their own
offsets — so there is no merge step at all and resume metadata stays per-segment.

- LAN / loopback: 1 stream already saturates (benchmark below confirms); N=2 default.
- WAN: N=4 — recovers congestion-window collapse losses.
- High latency (≥150 ms RTT): N=8 — also works around slow window growth.

Segments are only created for files > 8 MiB per segment; tiny files use one.

## 5. Network tuning (`TransferConfig` presets)

Socket buffers must hold at least one bandwidth-delay product (BDP) or the
TCP window can never fill the pipe:

| Profile | Assumption | BDP | SO_SNDBUF/SO_RCVBUF | Streams |
|---------|-----------|-----|---------------------|---------|
| `lan()` | 1–10 Gbps, <1 ms | ~125 KB | 0 = OS auto-tune (Linux auto-tuning beats fixed values here) | 2 |
| `wan()` | ≤500 Mbps, ~40 ms | ~2.5 MB | 4 MB | 4 |
| `highLatency()` | ~200 Mbps, ~300 ms | ~7.5 MB | 16 MB | 8 |

`TCP_NODELAY` is **on** for all connections: the protocol's control frames are
tiny and latency-sensitive (Nagle would stall them behind unacked data), while
bulk data always fills segments so Nagle never helps it anyway.
`SO_KEEPALIVE` plus an application-level idle watchdog (sender side) reaps
dead connections; closing the channel unblocks any stuck read.

## 6. Memory budget (40 GB transfer)

| Component | Cost |
|-----------|------|
| Sender data path | 0 heap (kernel-space sendfile) + ~16 B/frame |
| Receiver | 8 segments × 1 MiB direct buffer = 8 MB |
| Verification pass | 1 MiB buffer + 32 B digest state |
| Chunk CRC table | 40 GB / 4 MB × 4 B = 40 KB |
| Resume state | ~few hundred bytes |
| **Total** | **< 10 MB**, independent of file size |

Verified empirically: a 2 GiB round trip (transfer + SHA-256 verify) completes
under `-Xmx100m`.

## 7. Error handling & resume

- Every byte written is reflected in `ResumeState` before it is ever trusted;
  the `.resume` file is saved atomically (temp + `ATOMIC_MOVE`) every 32 MB
  per segment and on completion. A crash costs at most the unsaved window —
  re-fetched bytes just overwrite identical data.
- Connection reset / premature EOF / idle timeout → exponential backoff
  (1 s → 30 s cap), reconnect, `RANGE_REQUEST` from the exact saved offset,
  up to `maxRetries`. Auth failures and range rejections are not retried.
- Disk full fails fast at preallocation (free-space check + full-length
  truncate). Permission errors surface at file open, before any transfer.
- A file whose SHA-256 still mismatches after chunk repair is **rejected**
  (exception; the `.part` file is left for inspection/retry, never renamed to
  the final name).

## 8. Benchmarks & profiling

Measured (WSL2 loopback, 2 GiB, `-Xmx100m`, includes verification pass):
~250 MB/s for 1 stream and ~260 MB/s for 4 — disk-bound, as expected on
loopback where RTT ≈ 0.

Expectations elsewhere: 1 GbE LAN ≈ 110–118 MB/s (wire-limited, single
stream); 10 GbE ≈ 1+ GB/s (disk or single-stream CPU becomes the limit; use
2–4 streams); 300 Mbps/40 ms WAN ≈ 30–37 MB/s with `wan()` preset.

Bottleneck order to investigate when slow: ① network BDP vs socket buffers
(`ss -tin` — look at `cwnd`/retransmits), ② disk (`iostat -x 1` — receiver
write throughput), ③ CPU (`async-profiler` in CPU mode — should show almost
all time in `transferTo`/`read`; any significant memcpy or GC means a
regression), ④ verification pass (sequential read of the whole file; overlaps
nothing today and could be pipelined per-chunk as a future optimization).

## 9. Security

- **Per-transfer bearer tokens** (128-bit `SecureRandom`, constant-time
  comparison) required in the protocol HELLO and the HTTP download URL;
  nothing — not even the manifest — is served before authentication.
- **Path traversal**: filenames from manifests and multipart headers are
  reduced to their last path component and control-characters stripped
  (`FileReceiver.sanitizeFilename`), on both upload and download sides.
- **File validation**: senders serve exactly the single file they were
  created for; range and checksum requests are bounds-checked against it.
- Recommended next steps: TLS (wrap the protocol in `SSLEngine` or tunnel
  via TLS-terminating proxy) for confidentiality on untrusted networks;
  token expiry + download-count limits; rate limiting on the HTTP gateway.
