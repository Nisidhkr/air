package p2p.transfer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL {@link TransferRequestRepository} over the
 * {@code transfer_requests} table (V2 column names: sender_id, receiver_id,
 * status). Plain JDBC.
 */
public final class PgTransferRequestRepository implements TransferRequestRepository {

    private final String url;
    private final String user;
    private final String password;

    public PgTransferRequestRepository(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public static Optional<PgTransferRequestRepository> fromEnv() {
        String url = System.getenv("DATABASE_URL");
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new PgTransferRequestRepository(url,
                System.getenv().getOrDefault("DATABASE_USER", "fylo"),
                System.getenv().getOrDefault("DATABASE_PASSWORD", "")));
    }

    @Override
    public TransferRequestRecord save(TransferRequestRecord r) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO transfer_requests
                         (id, transfer_session_id, sender_id, receiver_id, status,
                          file_name, size_bytes, share_port, share_token,
                          expires_at, responded_at, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT (id) DO UPDATE SET
                         status = EXCLUDED.status,
                         responded_at = EXCLUDED.responded_at
                     """)) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.transferSessionId());
            ps.setObject(3, r.senderId());
            ps.setObject(4, r.receiverId());
            ps.setString(5, r.status());
            ps.setString(6, r.fileName());
            ps.setLong(7, r.fileSizeBytes());
            ps.setInt(8, r.sharePort());
            ps.setString(9, r.shareToken());
            ps.setTimestamp(10, Timestamp.from(r.expiresAt()));
            ps.setTimestamp(11, r.respondedAt() == null ? null : Timestamp.from(r.respondedAt()));
            ps.setTimestamp(12, Timestamp.from(r.createdAt()));
            ps.executeUpdate();
            return r;
        } catch (SQLException e) {
            throw new IllegalStateException("Transfer request save failed", e);
        }
    }

    @Override
    public Optional<TransferRequestRecord> findById(UUID id) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM transfer_requests WHERE id = ? AND deleted_at IS NULL")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Transfer request query failed", e);
        }
    }

    @Override
    public List<TransferRequestRecord> findPendingForReceiver(UUID receiverId) {
        List<TransferRequestRecord> results = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT * FROM transfer_requests
                     WHERE receiver_id = ? AND status = 'PENDING'
                       AND expires_at > now() AND deleted_at IS NULL
                     ORDER BY created_at DESC
                     """)) {
            ps.setObject(1, receiverId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Pending request query failed", e);
        }
        return results;
    }

    @Override
    public void updateStatus(UUID id, String status, Instant respondedAt) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE transfer_requests SET status = ?, responded_at = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setTimestamp(2, respondedAt == null ? null : Timestamp.from(respondedAt));
            ps.setObject(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Transfer request update failed", e);
        }
    }

    private static TransferRequestRecord map(ResultSet rs) throws SQLException {
        Timestamp responded = rs.getTimestamp("responded_at");
        return new TransferRequestRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("transfer_session_id", UUID.class),
                rs.getObject("sender_id", UUID.class),
                rs.getObject("receiver_id", UUID.class),
                rs.getString("status"),
                null, // message column not modeled in V1/V2 schema
                rs.getString("file_name"),
                rs.getLong("size_bytes"),
                rs.getInt("share_port"),
                rs.getString("share_token"),
                rs.getTimestamp("expires_at").toInstant(),
                responded == null ? null : responded.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
