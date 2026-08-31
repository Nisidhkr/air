package p2p.transfer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import p2p.utils.PersistedJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed {@link TransferSessionRepository}: single-node stand-in for
 * the PostgreSQL {@code transfer_sessions} table, persisted to
 * {@code transfer_sessions.json} on every change.
 */
public final class JsonTransferSessionRepository implements TransferSessionRepository {

    private final ObjectMapper json = PersistedJson.mapper();
    private final Path file;
    private final ConcurrentHashMap<UUID, TransferSessionRecord> byId = new ConcurrentHashMap<>();

    public JsonTransferSessionRepository(Path dataDir) {
        this.file = dataDir.resolve("transfer_sessions.json");
        load();
    }

    @Override
    public TransferSessionRecord save(TransferSessionRecord session) {
        byId.put(session.id(), session);
        persist();
        return session;
    }

    @Override
    public Optional<TransferSessionRecord> findBySessionCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return byId.values().stream()
                .filter(s -> code.equals(s.sessionCode()))
                .max(java.util.Comparator.comparing(TransferSessionRecord::createdAt));
    }

    @Override
    public Optional<TransferSessionRecord> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public void updateStatus(UUID id, String status) {
        mutate(id, s -> s.withStatus(status));
    }

    @Override
    public void updateBytesTransferred(UUID id, long bytes) {
        mutate(id, s -> s.withBytesTransferred(bytes));
    }

    @Override
    public void updateCompleted(UUID id, String status, Instant completedAt) {
        mutate(id, s -> s.withCompleted(status, completedAt));
    }

    private void mutate(UUID id, java.util.function.UnaryOperator<TransferSessionRecord> change) {
        if (byId.computeIfPresent(id, (k, s) -> change.apply(s)) != null) {
            persist();
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<TransferSessionRecord> sessions = json.readValue(Files.readAllBytes(file),
                    new TypeReference<>() {
                    });
            sessions.forEach(s -> byId.put(s.id(), s));
        } catch (IOException e) {
            System.err.println("Could not load transfer sessions: " + e.getMessage());
        }
    }

    private synchronized void persist() {
        PersistedJson.writeAtomic(json, file, List.copyOf(byId.values()));
    }
}
