package p2p.user;

import p2p.infra.RedisClient;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Online presence for logged-in users (Username Share needs to know whether
 * the receiver can accept a live transfer). A user is online while any of
 * their clients has touched presence within the TTL — clients refresh it by
 * polling notifications or calling any authenticated endpoint.
 *
 * <p>Backbone §14.3: with Redis, presence lives under
 * {@code presence:{userId}} with a 90 s TTL, so it is shared across all app
 * instances and expires server-side. Without Redis the original in-memory
 * map serves single-node installs. Last-seen is tracked locally either way
 * (best-effort, informational).
 */
public final class PresenceService {

    private static final long ONLINE_TTL_MS = 45_000;
    private static final int REDIS_TTL_SECONDS = 90;

    private final ConcurrentHashMap<String, Long> lastSeenByUserId = new ConcurrentHashMap<>();
    private final RedisClient redis; // null = in-memory only

    public PresenceService() {
        this(null);
    }

    public PresenceService(RedisClient redis) {
        this.redis = redis;
    }

    /** Called by the API layer on every authenticated request. */
    public void touch(String userId) {
        lastSeenByUserId.put(userId, System.currentTimeMillis());
        if (redisUp()) {
            redis.set("presence:" + userId, "1", REDIS_TTL_SECONDS);
        }
    }

    /** Backbone naming: heartbeat refresh (same semantics as touch). */
    public void markOnline(String userId) {
        touch(userId);
    }

    public void markOffline(String userId) {
        lastSeenByUserId.remove(userId);
        if (redisUp()) {
            redis.delete("presence:" + userId);
        }
    }

    public boolean isOnline(String userId) {
        if (redisUp()) {
            return redis.exists("presence:" + userId);
        }
        Long lastSeen = lastSeenByUserId.get(userId);
        return lastSeen != null && System.currentTimeMillis() - lastSeen < ONLINE_TTL_MS;
    }

    public long lastSeenEpochMs(String userId) {
        return lastSeenByUserId.getOrDefault(userId, 0L);
    }

    /** Local-node view (informational; exact per-node only). */
    public Set<String> onlineUserIds() {
        long cutoff = System.currentTimeMillis() - ONLINE_TTL_MS;
        return lastSeenByUserId.entrySet().stream()
                .filter(e -> e.getValue() >= cutoff)
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private boolean redisUp() {
        return redis != null && redis.isAvailable();
    }
}
