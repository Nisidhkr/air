package p2p.transfer;

import java.util.List;
import java.util.UUID;

/** Persistence port for the {@code transfer_history} ledger. */
public interface TransferHistoryRepository {

    void record(TransferHistoryRecord entry);

    /** Newest first; {@code page} is 0-based. */
    List<TransferHistoryRecord> findByUserId(UUID userId, int page, int size);
}
