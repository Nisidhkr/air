package p2p.auth;

import p2p.infra.RedisClient;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Refresh-token session registry with rotation: each refresh token is valid
 * for exactly one refresh; using it invalidates it and issues a successor.
 * A stolen-then-replayed token therefore dies on second use.
 *
 * <p>Keyed by the JWT {@code jti} claim, so only the id set — not the tokens
 * themselves — is held server-side.
 *
 * <p>Backbone §14.3: when Redis is available, sessions live under
 * {@code session:{jti}} (TTL = refresh lifetime) with a per-user index at
 * {@code sessions:user:{userId}} for logout-everywhere, and rotated-out jtis
 * are blacklisted for the access-token lifetime. Presence of Redis makes
 * sessions shared across all app instances; without it the original
 * in-memory map serves single-node deployments.
 */
public final class SessionService {

    private static final int BLACKLIST_TTL_SECONDS = 15 * 60; // access TTL

    private record Session(String userId, long expiresAtEpochSec) {
    }

    private final ConcurrentHashMap<String, Session> activeByTokenId = new ConcurrentHashMap<>();
    private final RedisClient redis; // null = in-memory only

    public SessionService() {
        this(null);
    }

    public SessionService(RedisClient redis) {
        this.redis = redis;
    }

    public void register(String tokenId, String userId, long expiresAtEpochSec) {
        if (redisUp()) {
            int ttl = (int) Math.max(1, expiresAtEpochSec - System.currentTimeMillis() / 1000);
            redis.set("session:" + tokenId, userId + "|" + expiresAtEpochSec, ttl);
            redis.addToSet("sessions:user:" + userId, tokenId);
            return;
        }
        activeByTokenId.put(tokenId, new Session(userId, expiresAtEpochSec));
        evictExpired();
    }

    /** Atomically consumes the session; false if unknown, expired, or replayed. */
    public boolean consume(String tokenId, String userId) {
        if (redisUp()) {
            if (redis.exists("blacklist:" + tokenId)) {
                return false;
            }
            String value = redis.getAndDelete("session:" + tokenId);
            if (value == null) {
                return false;
            }
            // Rotation blacklist: the consumed jti can never be replayed even
            // if a race re-registered it.
            redis.set("blacklist:" + tokenId, "1", BLACKLIST_TTL_SECONDS);
            int split = value.lastIndexOf('|');
            String owner = split == -1 ? value : value.substring(0, split);
            long expires = split == -1 ? 0 : Long.parseLong(value.substring(split + 1));
            return owner.equals(userId) && expires >= System.currentTimeMillis() / 1000;
        }
        Session session = activeByTokenId.remove(tokenId);
        return session != null
                && session.userId().equals(userId)
                && session.expiresAtEpochSec() >= System.currentTimeMillis() / 1000;
    }

    /** Logout-everywhere support. */
    public void revokeAllFor(String userId) {
        if (redisUp()) {
            for (String tokenId : redis.setMembers("sessions:user:" + userId)) {
                redis.delete("session:" + tokenId);
                redis.set("blacklist:" + tokenId, "1", BLACKLIST_TTL_SECONDS);
            }
            redis.delete("sessions:user:" + userId);
            return;
        }
        activeByTokenId.entrySet().removeIf(e -> e.getValue().userId().equals(userId));
    }

    private boolean redisUp() {
        return redis != null && redis.isAvailable();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis() / 1000;
        activeByTokenId.entrySet().removeIf(e -> e.getValue().expiresAtEpochSec() < now);
    }
}
