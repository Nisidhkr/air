package p2p.transfer;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the {@code transfer_sessions} table (backbone §5.2) — the
 * durable identity of a transfer in any mode. {@code fileName} is carried
 * beyond the backbone's minimum because the table requires it NOT NULL and
 * every flow knows it at creation time.
 */
public record TransferSessionRecord(
        UUID id,
        String mode,              // DIRECT, NEARBY, USERNAME, LINK
        UUID senderId,            // nullable (guest)
        UUID receiverId,          // nullable
        UUID fileId,              // nullable
        UUID shareLinkId,         // nullable
        String status,            // PENDING, CONNECTING, IN_PROGRESS, PAUSED,
                                  // COMPLETED, FAILED, CANCELLED
        String fileName,
        long bytesTransferred,
        Long totalBytes,          // nullable
        Integer lastChunkIndex,   // nullable
        String transferToken,
        String sessionCode,       // nullable (Mode 1 only)
        Instant startedAt,        // nullable
        Instant completedAt,      // nullable
        String errorMessage,      // nullable
        Instant createdAt,
        Instant updatedAt) {

    public TransferSessionRecord withStatus(String newStatus) {
        return new TransferSessionRecord(id, mode, senderId, receiverId, fileId, shareLinkId,
                newStatus, fileName, bytesTransferred, totalBytes, lastChunkIndex,
                transferToken, sessionCode, startedAt, completedAt, errorMessage,
                createdAt, Instant.now());
    }

    public TransferSessionRecord withBytesTransferred(long bytes) {
        return new TransferSessionRecord(id, mode, senderId, receiverId, fileId, shareLinkId,
                status, fileName, bytes, totalBytes, lastChunkIndex,
                transferToken, sessionCode, startedAt, completedAt, errorMessage,
                createdAt, Instant.now());
    }

    public TransferSessionRecord withCompleted(String finalStatus, Instant at) {
        return new TransferSessionRecord(id, mode, senderId, receiverId, fileId, shareLinkId,
                finalStatus, fileName, bytesTransferred, totalBytes, lastChunkIndex,
                transferToken, sessionCode, startedAt, at, errorMessage,
                createdAt, Instant.now());
    }
}
