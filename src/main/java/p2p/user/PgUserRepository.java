package p2p.user;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL {@link UserRepository} over the {@code users} table from
 * {@code db/migrations/V1__unified_schema.sql}. Selected at startup when
 * {@code DATABASE_URL} is configured; otherwise the JSON repository serves
 * single-node installs. Same interface — no service-layer change.
 *
 * <p>Uses one short-lived connection per operation via {@link DriverManager};
 * under real load put PgBouncer in front or swap in HikariCP here (the
 * change is contained to {@link #connect()}).
 */
public final class PgUserRepository implements UserRepository {

    private final String url;
    private final String user;
    private final String password;

    public PgUserRepository(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** Reads DATABASE_URL / DATABASE_USER / DATABASE_PASSWORD. */
    public static Optional<PgUserRepository> fromEnv() {
        String url = System.getenv("DATABASE_URL");
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new PgUserRepository(url,
                System.getenv().getOrDefault("DATABASE_USER", "fylo"),
                System.getenv().getOrDefault("DATABASE_PASSWORD", "")));
    }

    @Override
    public Optional<User> findById(String userId) {
        return queryOne("SELECT * FROM users WHERE id = ? AND deleted_at IS NULL",
                ps -> ps.setObject(1, UUID.fromString(userId)));
    }

    @Override
    public Optional<User> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return queryOne("SELECT * FROM users WHERE username = ? AND deleted_at IS NULL",
                ps -> ps.setString(1, normalize(username)));
    }

    @Override
    public List<User> searchByUsernamePrefix(String prefix, int limit) {
        List<User> results = new ArrayList<>();
        String needle = normalize(prefix).replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_");
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM users WHERE username LIKE ? ESCAPE '\\' "
                     + "AND deleted_at IS NULL ORDER BY username LIMIT ?")) {
            ps.setString(1, needle + "%");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("User search failed", e);
        }
        return results;
    }

    @Override
    public void save(User u) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO users (id, username, display_name, email, password_hash,
                                        plan_tier, storage_used, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT (id) DO UPDATE SET
                         display_name = EXCLUDED.display_name,
                         email = EXCLUDED.email,
                         password_hash = EXCLUDED.password_hash,
                         plan_tier = EXCLUDED.plan_tier
                     """)) {
            ps.setObject(1, UUID.fromString(u.userId()));
            ps.setString(2, u.username());
            ps.setString(3, u.displayName());
            ps.setString(4, u.email());
            ps.setString(5, u.passwordHash());
            ps.setString(6, u.planType() == null ? "FREE" : u.planType());
            ps.setLong(7, u.storageUsedBytes());
            ps.setTimestamp(8, new Timestamp(u.createdAtEpochMs()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("User save failed", e);
        }
    }

    @Override
    public void incrementStorageUsed(String userId, long deltaBytes) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET storage_used = GREATEST(0, storage_used + ?) WHERE id = ?")) {
            ps.setLong(1, deltaBytes);
            ps.setObject(2, UUID.fromString(userId));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Storage accounting update failed", e);
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Optional<User> queryOne(String sql, Binder binder) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("User query failed", e);
        }
    }

    private static User map(ResultSet rs) throws SQLException {
        return new User(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("plan_tier"),
                rs.getLong("storage_used"),
                rs.getTimestamp("created_at").getTime());
    }

    /** Health probe: SELECT 1 (backbone §14.4). */
    public boolean ping() {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT 1")) {
            return ps.executeQuery().next();
        } catch (SQLException e) {
            return false;
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private static String normalize(String username) {
        String value = username.strip().toLowerCase(Locale.ROOT);
        return value.startsWith("@") ? value.substring(1) : value;
    }
}
