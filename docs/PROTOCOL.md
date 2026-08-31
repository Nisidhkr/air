# Air Transfer Protocol — Version 1

Binary protocol spoken between `FileSender` (the peer offering a file) and any
client (`FileReceiver`, or the HTTP gateway in `FileController`). It replaces
the v0 text header (`Filename: <name>\n` + raw bytes).

All integers are **big-endian**. Strings are a 2-byte unsigned length followed
by UTF-8 bytes.

## Frame format

Every message except bulk file data is a frame:

```
+---------+---------+--------+----------------+----------------------+
| MAGIC   | VERSION | TYPE   | PAYLOAD_LENGTH | PAYLOAD              |
| 4 bytes | 1 byte  | 1 byte | 4 bytes        | PAYLOAD_LENGTH bytes |
+---------+---------+--------+----------------+----------------------+
```

- `MAGIC` = `0x504C4E4B` (`"PLNK"`). Anything else aborts the connection.
- `VERSION` = `1`. Receivers reject other versions, enabling future evolution.
- `PAYLOAD_LENGTH` is capped at 1 MiB; control frames are small by design.

Bulk file data is deliberately **not** framed: it streams raw between
`DATA_START` and `COMPLETE` so the sender can use zero-copy
`FileChannel.transferTo` (`sendfile(2)`).

## Message types

| Type | Name                     | Direction | Payload |
|------|--------------------------|-----------|---------|
| 0x01 | HELLO                    | C → S     | `token` (string) |
| 0x02 | MANIFEST                 | S → C     | see below |
| 0x03 | RANGE_REQUEST            | C → S     | `offset` (8), `length` (8; `-1` = to EOF) |
| 0x04 | DATA_START               | S → C     | `offset` (8), `length` (8) — `length` raw bytes follow |
| 0x05 | CHUNK_CHECKSUM_REQUEST   | C → S     | `firstChunk` (8), `count` (4) |
| 0x06 | CHUNK_CHECKSUMS          | S → C     | `firstChunk` (8), `count` (4), `count` × CRC32C (4) |
| 0x07 | COMPLETE                 | S → C     | `bytesSent` (8) — transfer completion marker |
| 0x7F | ERROR                    | either    | `code` (2), `message` (string) |

### MANIFEST payload

```
transferId      16 bytes   UUID; changes if the offered file changes
filename        string     display name only — receivers MUST sanitize
fileSize        8 bytes    signed long (supports > 8 EB; 40 GB is trivial)
chunkSize       4 bytes    checksum granularity (default 4 MiB)
sha256Length    1 byte     0 = hash not available, else 32
sha256          0/32 bytes whole-file SHA-256
metadataLength  2 bytes
metadata        N bytes    application-defined (currently JSON)
```

### Error codes

| Code | Meaning        | Retryable |
|------|----------------|-----------|
| 1    | AUTH_FAILED    | no |
| 2    | INVALID_RANGE  | no |
| 3    | NOT_FOUND      | no |
| 4    | INTERNAL       | yes |
| 5    | BAD_REQUEST    | no |

## Session flow

```
Client                                  Server
  |---------- HELLO(token) --------------->|   token checked in constant time
  |<--------- MANIFEST --------------------|   (or ERROR(AUTH_FAILED), close)
  |---------- RANGE_REQUEST(off, len) ---->|
  |<--------- DATA_START(off, len) --------|
  |<========= len raw bytes ==============>|   zero-copy sendfile path
  |<--------- COMPLETE(len) ---------------|
  |   ... more RANGE/CHECKSUM requests on the same connection, or close ...
```

- **Resume** is just a `RANGE_REQUEST` whose `offset` is the number of bytes
  already on disk. The server is stateless about client progress.
- **Parallel download**: the client opens N connections and requests N
  disjoint ranges.
- **Integrity repair**: after a whole-file SHA-256 mismatch the client fetches
  per-chunk CRC32C lists, diffs them against locally computed CRCs, and
  re-requests only the corrupt chunks.
