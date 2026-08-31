package p2p.share;

import p2p.engine.ShareCodes;
import p2p.engine.TransferEngine;
import p2p.engine.TransferSource;
import p2p.security.TransferTokens;
import p2p.transfer.TransferSessionRecord;
import p2p.transfer.TransferSessionRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * MODE 1 — Direct Share (guest, live). Sender uploads a file to their own
 * node, gets a share code / QR payload; the receiver connects with the code
 * and pulls straight from the sender through the one engine. Nothing is
 * persisted; when the sender's share closes, the code is dead.
 */
public final class DirectShareService {

    /** Everything the UI needs to show after offering a file. */
    public record DirectShare(int port, String token, String code, String qrPayload,
                              String fileName, long size) {
    }

    /**
     * A pre-transfer rendezvous session (backbone §11 Mode 1):
     * sender announces file metadata, gets a 6-char code; receiver joins with
     * the code and receives the transfer token. Bytes then flow through the
     * one engine (upload/download or the binary protocol). Live-only: if the
     * sender never uploads, or the session expires, the code dies.
     */
    public record DirectSession(String sessionId, String sessionCode, String transferToken,
                                String fileName, long fileSizeBytes, String checksum,
                                long expiresAtEpochMs, State state) {

        public enum State { PENDING, CONNECTING }

        public boolean expired() {
            return System.currentTimeMillis() > expiresAtEpochMs;
        }
    }

    private static final long SESSION_TTL_MS = 10 * 60_000; // backbone: 10 minutes

    private final TransferEngine engine;
    private final TransferSessionRepository sessions;

    public DirectShareService(TransferEngine engine, TransferSessionRepository sessions) {
        this.engine = engine;
        this.sessions = sessions;
    }

    /**
     * Sender creates a rendezvous session and gets its code + token. The
     * session is persisted to {@code transfer_sessions} (backbone §5.2), so
     * with PostgreSQL any backend instance can answer the join.
     */
    public DirectSession initiate(String fileName, long fileSizeBytes, String checksum) {
        String code = ShareCodes.generateSessionCode();
        Instant now = Instant.now();
        TransferSessionRecord record = new TransferSessionRecord(
                UUID.randomUUID(), "DIRECT", null, null, null, null,
                "PENDING", fileName, 0, fileSizeBytes, null,
                TransferTokens.generate(), code, null, null, null, now, now);
        sessions.save(record);
        return new DirectSession(record.id().toString(), code, record.transferToken(),
                fileName, fileSizeBytes, checksum,
                now.toEpochMilli() + SESSION_TTL_MS, DirectSession.State.PENDING);
    }

    /** Receiver presents the code; on success the session flips to CONNECTING. */
    public Optional<DirectSession> join(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.strip().toUpperCase(java.util.Locale.ROOT);
        return sessions.findBySessionCode(normalized)
                .filter(s -> "PENDING".equals(s.status()))
                .filter(s -> s.createdAt().toEpochMilli() + SESSION_TTL_MS
                        > System.currentTimeMillis())
                .map(s -> {
                    sessions.updateStatus(s.id(), "CONNECTING");
                    return new DirectSession(s.id().toString(), s.sessionCode(),
                            s.transferToken(), s.fileName(),
                            s.totalBytes() == null ? 0 : s.totalBytes(), null,
                            s.createdAt().toEpochMilli() + SESSION_TTL_MS,
                            DirectSession.State.CONNECTING);
                });
    }

    /** Offers an already-uploaded (or local) file and mints its share code. */
    public DirectShare create(Path file, String relativePath) throws IOException {
        String displayName = p2p.utils.MultipartUploads.stripUploadPrefix(
                file.getFileName().toString());
        TransferEngine.Share share = engine.offer(new TransferSource.LiveFile(
                file, displayName, Files.size(file), relativePath));
        return toView(share);
    }

    /** Resolves a share code typed or scanned by a receiver. */
    public Optional<ShareCodes.Code> resolve(String rawCode) {
        return ShareCodes.parse(rawCode);
    }

    public void stop(int port) {
        engine.stopShare(port);
    }

    public Map<String, Object> asJson(DirectShare share) {
        return Map.of(
                "port", share.port(),
                "token", share.token(),
                "code", share.code(),
                "qr", share.qrPayload(),
                "name", share.fileName(),
                "size", share.size());
    }

    private static DirectShare toView(TransferEngine.Share share) {
        return new DirectShare(share.port(), share.token(), share.code().encoded(),
                share.code().qrPayload(), share.fileName(), share.size());
    }
}
