package p2p.transfer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@code transfer_requests} (backbone §5.2). */
public interface TransferRequestRepository {

    TransferRequestRecord save(TransferRequestRecord request);

    Optional<TransferRequestRecord> findById(UUID id);

    List<TransferRequestRecord> findPendingForReceiver(UUID receiverId);

    void updateStatus(UUID id, String status, Instant respondedAt);
}
