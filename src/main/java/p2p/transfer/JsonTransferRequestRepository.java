package p2p.transfer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import p2p.utils.PersistedJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** File-backed {@link TransferRequestRepository} (single-node stand-in). */
public final class JsonTransferRequestRepository implements TransferRequestRepository {

    private final ObjectMapper json = PersistedJson.mapper();
    private final Path file;
    private final ConcurrentHashMap<UUID, TransferRequestRecord> byId = new ConcurrentHashMap<>();

    public JsonTransferRequestRepository(Path dataDir) {
        this.file = dataDir.resolve("transfer_requests.json");
        load();
    }

    @Override
    public TransferRequestRecord save(TransferRequestRecord request) {
        byId.put(request.id(), request);
        persist();
        return request;
    }

    @Override
    public Optional<TransferRequestRecord> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<TransferRequestRecord> findPendingForReceiver(UUID receiverId) {
        Instant now = Instant.now();
        return byId.values().stream()
                .filter(r -> r.receiverId().equals(receiverId)
                        && "PENDING".equals(r.status())
                        && r.expiresAt().isAfter(now))
                .sorted(Comparator.comparing(TransferRequestRecord::createdAt).reversed())
                .toList();
    }

    @Override
    public void updateStatus(UUID id, String status, Instant respondedAt) {
        if (byId.computeIfPresent(id, (k, r) -> r.withStatus(status, respondedAt)) != null) {
            persist();
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<TransferRequestRecord> requests = json.readValue(Files.readAllBytes(file),
                    new TypeReference<>() {
                    });
            requests.forEach(r -> byId.put(r.id(), r));
        } catch (IOException e) {
            System.err.println("Could not load transfer requests: " + e.getMessage());
        }
    }

    private synchronized void persist() {
        PersistedJson.writeAtomic(json, file, List.copyOf(byId.values()));
    }
}
