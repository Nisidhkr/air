package p2p.user;

import p2p.transfer.TransferHistoryRecord;
import p2p.transfer.TransferHistoryRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Account-scoped transfer history (mode 3/4 requirement). Device-local queue
 * history stays in {@code TransferManager}; this records the durable,
 * user-visible ledger through the ONE {@link TransferHistoryRepository}
 * (JSON file single-node, PostgreSQL {@code transfer_history} when
 * DATABASE_URL is set).
 */
public final class TransferHistoryService {

    public enum Mode { DIRECT, NEARBY, USERNAME, LINK }

    /** API-facing view (kept stable across the repository migration). */
    public record Entry(String id, String userId, String direction, Mode mode, String fileName,
                        long sizeBytes, String counterparty, String status, long atEpochMs) {
    }

    private final TransferHistoryRepository repository;

    public TransferHistoryService(TransferHistoryRepository repository) {
        this.repository = repository;
    }

    public void record(String userId, String direction, Mode mode, String fileName,
                       long sizeBytes, String counterparty, String status) {
        record(userId, direction, mode, fileName, sizeBytes, counterparty, status, null);
    }

    public void record(String userId, String direction, Mode mode, String fileName,
                       long sizeBytes, String counterparty, String status,
                       UUID transferSessionId) {
        repository.record(new TransferHistoryRecord(
                UUID.randomUUID(), transferSessionId, UUID.fromString(userId),
                "SEND".equals(direction) ? "SENDER" : "RECEIVER",
                mode.name(), fileName, sizeBytes, status, counterparty, Instant.now()));
    }

    public List<Entry> listFor(String userId, int limit) {
        return repository.findByUserId(UUID.fromString(userId), 0, limit).stream()
                .map(r -> new Entry(
                        r.id().toString(),
                        r.userId().toString(),
                        "SENDER".equals(r.role()) ? "SEND" : "RECEIVE",
                        Mode.valueOf(r.mode()),
                        r.fileName(),
                        r.fileSizeBytes() == null ? 0 : r.fileSizeBytes(),
                        r.peerDisplayName(),
                        r.status(),
                        r.createdAt().toEpochMilli()))
                .toList();
    }
}
