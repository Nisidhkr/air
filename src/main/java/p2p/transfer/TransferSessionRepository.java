package p2p.transfer;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@code transfer_sessions} (backbone §5.2). JSON-file
 * implementation for single-node installs; PostgreSQL when DATABASE_URL is
 * set — same interface, no service-layer change.
 */
public interface TransferSessionRepository {

    TransferSessionRecord save(TransferSessionRecord session);

    Optional<TransferSessionRecord> findBySessionCode(String code);

    Optional<TransferSessionRecord> findById(UUID id);

    void updateStatus(UUID id, String status);

    void updateBytesTransferred(UUID id, long bytes);

    void updateCompleted(UUID id, String status, Instant completedAt);
}
