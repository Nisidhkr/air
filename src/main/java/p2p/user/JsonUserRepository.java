package p2p.user;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed {@link UserRepository}: all users in memory, persisted to
 * {@code users.json} on every change (atomic replace via temp file). This is
 * the single-node stand-in for the PostgreSQL {@code users} table and is
 * deliberately trivial to migrate: same interface, same record shape.
 */
public final class JsonUserRepository implements UserRepository {

    private final ObjectMapper json = new ObjectMapper();
    private final Path file;
    private final ConcurrentHashMap<String, User> byId = new ConcurrentHashMap<>();

    public JsonUserRepository(Path dataDir) {
        this.file = dataDir.resolve("users.json");
        load();
    }

    @Override
    public Optional<User> findById(String userId) {
        return Optional.ofNullable(byId.get(userId));
    }

    @Override
    public Optional<User> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        String needle = normalize(username);
        return byId.values().stream()
                .filter(u -> normalize(u.username()).equals(needle))
                .findFirst();
    }

    @Override
    public List<User> searchByUsernamePrefix(String prefix, int limit) {
        String needle = normalize(prefix);
        return byId.values().stream()
                .filter(u -> normalize(u.username()).startsWith(needle))
                .sorted(Comparator.comparing(User::username))
                .limit(limit)
                .toList();
    }

    @Override
    public void save(User user) {
        byId.put(user.userId(), user);
        persist();
    }

    @Override
    public void incrementStorageUsed(String userId, long deltaBytes) {
        User updated = byId.computeIfPresent(userId,
                (id, u) -> u.withStorageUsedBytes(u.storageUsedBytes() + deltaBytes));
        if (updated != null) {
            persist();
        }
    }

    private static String normalize(String username) {
        String value = username.strip().toLowerCase(Locale.ROOT);
        return value.startsWith("@") ? value.substring(1) : value;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<User> users = json.readValue(Files.readAllBytes(file), new TypeReference<>() {
            });
            users.forEach(u -> byId.put(u.userId(), u));
        } catch (IOException e) {
            System.err.println("Could not load users: " + e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), List.copyOf(byId.values()));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Could not persist users: " + e.getMessage());
        }
    }
}
