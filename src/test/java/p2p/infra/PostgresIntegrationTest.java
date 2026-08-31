package p2p.infra;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import p2p.transfer.PgTransferRequestRepository;
import p2p.transfer.PgTransferSessionRepository;
import p2p.transfer.TransferRequestRecord;
import p2p.transfer.TransferSessionRecord;
import p2p.user.PgUserRepository;
import p2p.user.User;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backbone §18.1: repositories against REAL PostgreSQL 16 with the real
 * V1+V2+V3 migrations applied. Requires a Docker daemon
 * (skip with -DexcludedGroups=integration when unavailable).
 */
@Tag("integration")
@Testcontainers
class PostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fylo").withUsername("fylo").withPassword("test");

    static PgUserRepository users;
    static PgTransferSessionRepository sessions;
    static PgTransferRequestRepository requests;

    @BeforeAll
    static void applyMigrations() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = conn.createStatement()) {
            for (String migration : new String[]{
                    "db/migrations/V1__unified_schema.sql",
                    "db/migrations/V2__fix_schema_alignment.sql",
                    "db/migrations/V3__transfer_runtime_columns.sql"}) {
                statement.execute(Files.readString(Path.of(migration)));
            }
        }
        users = new PgUserRepository(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        sessions = new PgTransferSessionRepository(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        requests = new PgTransferRequestRepository(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static User newUser(String username) {
        User user = new User(UUID.randomUUID().toString(), username, username,
                username + "@fylo.app", "pbkdf2$1$x$y", "FREE", 0,
                System.currentTimeMillis());
        users.save(user);
        return user;
    }

    @Test
    void pgUserRepository_registerAndFindByUsername() {
        User saved = newUser("testuser");
        Optional<User> found = users.findByUsername("testuser");
        assertTrue(found.isPresent());
        assertEquals(saved.userId(), found.get().userId());
        assertEquals("testuser@fylo.app", found.get().email());
        assertEquals("FREE", found.get().planType());
    }

    @Test
    void pgUserRepository_incrementStorageUsed() {
        User saved = newUser("storageuser");
        users.incrementStorageUsed(saved.userId(), 1_000_000);
        assertEquals(1_000_000,
                users.findByUsername("storageuser").orElseThrow().storageUsedBytes());
        users.incrementStorageUsed(saved.userId(), -2_000_000); // clamped at 0
        assertEquals(0, users.findByUsername("storageuser").orElseThrow().storageUsedBytes());
    }

    @Test
    void pgTransferSessionRepository_saveAndFindByCode() {
        Instant now = Instant.now();
        TransferSessionRecord session = new TransferSessionRecord(
                UUID.randomUUID(), "DIRECT", null, null, null, null,
                "PENDING", "test.txt", 0, 1024L, null, "tok-123", "TEST01",
                null, null, null, now, now);
        sessions.save(session);

        Optional<TransferSessionRecord> found = sessions.findBySessionCode("TEST01");
        assertTrue(found.isPresent());
        assertEquals("DIRECT", found.get().mode());
        assertEquals("tok-123", found.get().transferToken());

        sessions.updateStatus(session.id(), "CONNECTING");
        assertEquals("CONNECTING", sessions.findById(session.id()).orElseThrow().status());
    }

    @Test
    void pgTransferRequestRepository_pendingForReceiver() {
        User sender = newUser("req_sender");
        User receiver = newUser("req_receiver");
        Instant now = Instant.now();
        TransferSessionRecord session = new TransferSessionRecord(
                UUID.randomUUID(), "USERNAME", UUID.fromString(sender.userId()),
                UUID.fromString(receiver.userId()), null, null,
                "PENDING", "photo.jpg", 0, 5_242_880L, null, "tok-9", null,
                null, null, null, now, now);
        sessions.save(session);
        requests.save(new TransferRequestRecord(
                UUID.randomUUID(), session.id(),
                UUID.fromString(sender.userId()), UUID.fromString(receiver.userId()),
                "PENDING", null, "photo.jpg", 5_242_880L, 40000, "tok-9",
                now.plusSeconds(600), null, now));

        var pending = requests.findPendingForReceiver(UUID.fromString(receiver.userId()));
        assertEquals(1, pending.size());
        assertEquals("photo.jpg", pending.get(0).fileName());

        requests.updateStatus(pending.get(0).id(), "ACCEPTED", Instant.now());
        assertTrue(requests.findPendingForReceiver(
                UUID.fromString(receiver.userId())).isEmpty());
    }
}
