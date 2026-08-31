package p2p.transfer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL {@link TransferSessionRepository} over the
 * {@code transfer_sessions} table (V1+V2+V3 migrations). Plain JDBC,
 * one short-lived connection per operation (PgBouncer/Hikari slot in at
 * {@link #connect()} under load).
 */
public final class PgTransferSessionRepository implements TransferSessionRepository {

    private final String url;
    private final String user;
    private final String password;

    public PgTransferSessionRepository(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public static Optional<PgTransferSessionRepository> fromEnv() {
        String url = System.getenv("DATABASE_URL");
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new PgTransferSessionRepository(url,
                System.getenv().getOrDefault("DATABASE_USER", "fylo"),
                System.getenv().getOrDefault("DATABASE_PASSWORD", "")));
    }

    @Override
    public TransferSessionRecord save(TransferSessionRecord s) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO transfer_sessions
                         (id, mode, direction, status, file_name, size_bytes,
                          transferred_bytes, sender_id, receiver_id, file_id,
                          share_link_id, transfer_token, last_chunk_index,
                          session_code, started_at, finished_at, error, created_at)
                     VALUES (?, ?, 'SEND', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT (id) DO UPDATE SET
                         status = EXCLUDED.status,
                         transferred_bytes = EXCLUDED.transferred_bytes,
                         last_chunk_index = EXCLUDED.last_chunk_index,
                         finished_at = EXCLUDED.finished_at,
                         error = EXCLUDED.error
                     """)) {
            ps.setObject(1, s.id());
            ps.setString(2, s.mode());
            ps.setString(3, s.status());
            ps.setString(4, s.fileName() == null ? "file" : s.fileName());
            ps.setLong(5, s.totalBytes() == null ? 0 : s.totalBytes());
            ps.setLong(6, s.bytesTransferred());
            ps.setObject(7, s.senderId());
            ps.setObject(8, s.receiverId());
            ps.setObject(9, s.fileId());
            ps.setObject(10, s.shareLinkId());
            ps.setString(11, s.transferToken());
            ps.setObject(12, s.lastChunkIndex());
            ps.setString(13, s.sessionCode());
            ps.setTimestamp(14, toTimestamp(s.startedAt()));
            ps.setTimestamp(15, toTimestamp(s.completedAt()));
            ps.setString(16, s.errorMessage());
            ps.setTimestamp(17, toTimestamp(s.createdAt()));
            ps.executeUpdate();
            return s;
        } catch (SQLException e) {
            throw new IllegalStateException("Transfer session save failed", e);
        }
    }

    @Override
    public Optional<TransferSessionRecord> findBySessionCode(String code) {
        return queryOne("SELECT * FROM transfer_sessions WHERE session_code = ? "
                + "AND deleted_at IS NULL ORDER BY created_at DESC LIMIT 1", code);
    }

    @Override
    public Optional<TransferSessionRecord> findById(UUID id) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM transfer_sessions WHERE id = ? AND deleted_at IS NULL")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Transfer session query failed", e);
        }
    }

    @Override
    public void updateStatus(UUID id, String status) {
        execute("UPDATE transfer_sessions SET status = ? WHERE id = ?",
                ps -> {
                    ps.setString(1, status);
                    ps.setObject(2, id);
                });
    }

    @Override
    public void updateBytesTransferred(UUID id, long bytes) {
        execute("UPDATE transfer_sessions SET transferred_bytes = ? WHERE id = ?",
                ps -> {
                    ps.setLong(1, bytes);
                    ps.setObject(2, id);
                });
    }

    @Override
    public void updateCompleted(UUID id, String status, Instant completedAt) {
        execute("UPDATE transfer_sessions SET status = ?, finished_at = ? WHERE id = ?",
                ps -> {
                    ps.setString(1, status);
                    ps.setTimestamp(2, toTimestamp(completedAt));
                    ps.setObject(3, id);
                });
    }

    private Optional<TransferSessionRecord> queryOne(String sql, String param) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Transfer session query failed", e);
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private void execute(String sql, Binder binder) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Transfer session update failed", e);
        }
    }

    private static TransferSessionRecord map(ResultSet rs) throws SQLException {
        long size = rs.getLong("size_bytes");
        int lastChunk = rs.getInt("last_chunk_index");
        boolean lastChunkNull = rs.wasNull();
        return new TransferSessionRecord(
                rs.getObject("id", UUID.class),
                rs.getString("mode"),
                rs.getObject("sender_id", UUID.class),
                rs.getObject("receiver_id", UUID.class),
                rs.getObject("file_id", UUID.class),
                rs.getObject("share_link_id", UUID.class),
                rs.getString("status"),
                rs.getString("file_name"),
                rs.getLong("transferred_bytes"),
                size,
                lastChunkNull ? null : lastChunk,
                rs.getString("transfer_token"),
                rs.getString("session_code"),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("finished_at")),
                rs.getString("error"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
