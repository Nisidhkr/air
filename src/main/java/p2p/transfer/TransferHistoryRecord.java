package p2p.transfer;

import java.time.Instant;
import java.util.UUID;

/** One row of the {@code transfer_history} ledger (backbone §5.2). */
public record TransferHistoryRecord(
        UUID id,
        UUID transferSessionId,   // nullable
        UUID userId,
        String role,              // SENDER or RECEIVER
        String mode,              // DIRECT, NEARBY, USERNAME, LINK
        String fileName,
        Long fileSizeBytes,
        String status,
        String peerDisplayName,   // nullable
        Instant createdAt) {
}
