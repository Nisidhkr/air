package p2p.transfer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL {@link TransferHistoryRepository} over {@code transfer_history}
 * (record role ↔ column direction; peerDisplayName ↔ counterparty).
 */
public final class PgTransferHistoryRepository implements TransferHistoryRepository {

    private final String url;
    private final String user;
    private final String password;

    public PgTransferHistoryRepository(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public static Optional<PgTransferHistoryRepository> fromEnv() {
        String url = System.getenv("DATABASE_URL");
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new PgTransferHistoryRepository(url,
                System.getenv().getOrDefault("DATABASE_USER", "fylo"),
                System.getenv().getOrDefault("DATABASE_PASSWORD", "")));
    }

    @Override
    public void record(TransferHistoryRecord entry) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO transfer_history
                         (id, transfer_session_id, user_id, direction, mode,
                          file_name, size_bytes, counterparty, status, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setObject(1, entry.id());
            ps.setObject(2, entry.transferSessionId());
            ps.setObject(3, entry.userId());
            ps.setString(4, "SENDER".equals(entry.role()) ? "SEND" : "RECEIVE");
            ps.setString(5, entry.mode());
            ps.setString(6, entry.fileName());
            ps.setLong(7, entry.fileSizeBytes() == null ? 0 : entry.fileSizeBytes());
            ps.setString(8, entry.peerDisplayName());
            ps.setString(9, entry.status());
            ps.setTimestamp(10, Timestamp.from(entry.createdAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("History insert failed", e);
        }
    }

    @Override
    public List<TransferHistoryRecord> findByUserId(UUID userId, int page, int size) {
        List<TransferHistoryRecord> results = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT * FROM transfer_history
                     WHERE user_id = ? AND deleted_at IS NULL
                     ORDER BY created_at DESC LIMIT ? OFFSET ?
                     """)) {
            ps.setObject(1, userId);
            ps.setInt(2, size);
            ps.setInt(3, page * size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new TransferHistoryRecord(
                            rs.getObject("id", UUID.class),
                            rs.getObject("transfer_session_id", UUID.class),
                            rs.getObject("user_id", UUID.class),
                            "SEND".equals(rs.getString("direction")) ? "SENDER" : "RECEIVER",
                            rs.getString("mode"),
                            rs.getString("file_name"),
                            rs.getLong("size_bytes"),
                            rs.getString("status"),
                            rs.getString("counterparty"),
                            rs.getTimestamp("created_at").toInstant()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("History query failed", e);
        }
        return results;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
