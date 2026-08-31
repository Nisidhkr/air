package p2p.user;

/**
 * A registered account (modes 3 and 4 only; Direct and Nearby Share never
 * require one). The password hash format is owned by
 * {@link p2p.auth.PasswordHasher}.
 *
 * <p>Backbone §8.3 alignment: carries {@code email}, {@code planType}
 * ("FREE"/"PREMIUM" — mirrors {@code users.plan_tier}) and
 * {@code storageUsedBytes} (mirrors {@code users.storage_used}) so the JWT
 * plan claim and storage-quota checks read straight off the user row.
 */
public record User(
        String userId,
        String username,
        String displayName,
        String email,
        String passwordHash,
        String planType,
        long storageUsedBytes,
        long createdAtEpochMs) {

    /** Public projection — never leaks the password hash or email. */
    public record Profile(String userId, String username, String displayName, boolean online) {
    }

    public Profile profile(boolean online) {
        return new Profile(userId, username, displayName, online);
    }

    public User withStorageUsedBytes(long newUsed) {
        return new User(userId, username, displayName, email, passwordHash, planType,
                Math.max(0, newUsed), createdAtEpochMs);
    }

    public User withPlanType(String newPlanType) {
        return new User(userId, username, displayName, email, passwordHash, newPlanType,
                storageUsedBytes, createdAtEpochMs);
    }
}
