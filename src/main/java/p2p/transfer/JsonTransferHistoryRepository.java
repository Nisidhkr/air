package p2p.transfer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import p2p.utils.PersistedJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/** File-backed {@link TransferHistoryRepository} with a bounded ring. */
public final class JsonTransferHistoryRepository implements TransferHistoryRepository {

    private static final int MAX_ENTRIES = 5_000;

    private final ObjectMapper json = PersistedJson.mapper();
    private final Path file;
    private final ConcurrentLinkedDeque<TransferHistoryRecord> entries =
            new ConcurrentLinkedDeque<>();

    public JsonTransferHistoryRepository(Path dataDir) {
        this.file = dataDir.resolve("transfer_history.json");
        load();
    }

    @Override
    public void record(TransferHistoryRecord entry) {
        entries.addLast(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.pollFirst();
        }
        persist();
    }

    @Override
    public List<TransferHistoryRecord> findByUserId(UUID userId, int page, int size) {
        return entries.stream()
                .filter(e -> e.userId().equals(userId))
                .sorted(Comparator.comparing(TransferHistoryRecord::createdAt).reversed())
                .skip((long) page * size)
                .limit(size)
                .toList();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<TransferHistoryRecord> loaded = json.readValue(Files.readAllBytes(file),
                    new TypeReference<>() {
                    });
            entries.addAll(loaded);
        } catch (IOException e) {
            System.err.println("Could not load transfer history: " + e.getMessage());
        }
    }

    private synchronized void persist() {
        PersistedJson.writeAtomic(json, file, List.copyOf(entries));
    }
}
