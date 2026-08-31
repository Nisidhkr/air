package p2p.transfer;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the {@code transfer_requests} table (backbone §5.2 — the
 * Mode 3 handshake). Carries the file metadata and connection secret the
 * table requires NOT NULL; the share token is revealed to the receiver only
 * on accept.
 */
public record TransferRequestRecord(
        UUID id,
        UUID transferSessionId,
        UUID senderId,
        UUID receiverId,
        String status,            // PENDING, ACCEPTED, REJECTED, EXPIRED
        String message,           // nullable
        String fileName,
        long fileSizeBytes,
        int sharePort,
        String shareToken,
        Instant expiresAt,        // default now + 10 minutes
        Instant respondedAt,      // nullable
        Instant createdAt) {

    public TransferRequestRecord withStatus(String newStatus, Instant respondedAt) {
        return new TransferRequestRecord(id, transferSessionId, senderId, receiverId,
                newStatus, message, fileName, fileSizeBytes, sharePort, shareToken,
                expiresAt, respondedAt, createdAt);
    }
}
