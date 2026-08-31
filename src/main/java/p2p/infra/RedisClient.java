package p2p.infra;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.SetParams;

import java.io.Closeable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The ONE Redis access point (backbone §14.3): rate-limit counters,
 * session/presence state, and WebSocket pub/sub for multi-instance
 * deployments.
 *
 * <p>Redis is OPTIONAL: when it is unreachable (dev/test, single node),
 * {@link #isAvailable()} returns false and every operation degrades to a
 * harmless no-op — callers keep their in-memory fallbacks. Availability is
 * re-probed at most every 15 seconds so a Redis restart heals without an
 * app restart.
 *
 * <p>Subclassable on purpose: tests override {@link #isAvailable()} and the
 * data operations to simulate Redis without a server.
 */
public class RedisClient implements Closeable {

    private static final long PROBE_INTERVAL_NANOS = 15_000_000_000L;

    private final JedisPooled jedis;      // null = permanently disabled
    private final boolean configured;     // REDIS_URL explicitly set
    private volatile boolean available;
    private volatile long lastProbeNanos;

    protected RedisClient() { // for test doubles
        this.jedis = null;
        this.configured = false;
        this.available = false;
    }

    private RedisClient(JedisPooled jedis, boolean configured) {
        this.jedis = jedis;
        this.configured = configured;
        this.lastProbeNanos = -PROBE_INTERVAL_NANOS;
        probe();
    }

    /** Reads REDIS_URL (default redis://localhost:6379). Never throws. */
    public static RedisClient fromEnv() {
        String url = System.getenv("REDIS_URL");
        boolean configured = url != null && !url.isBlank();
        String effective = configured ? url : "redis://localhost:6379";
        try {
            return new RedisClient(new JedisPooled(java.net.URI.create(effective)), configured);
        } catch (RuntimeException e) {
            System.err.println("Redis disabled (" + e.getMessage() + "); using in-memory fallbacks");
            return disabled();
        }
    }

    /** A client that is never available — pure in-memory mode. */
    public static RedisClient disabled() {
        return new RedisClient();
    }

    /** True only when REDIS_URL was explicitly configured (health semantics). */
    public boolean isConfigured() {
        return configured;
    }

    public boolean isAvailable() {
        if (jedis == null) {
            return false;
        }
        long now = System.nanoTime();
        if (now - lastProbeNanos > PROBE_INTERVAL_NANOS) {
            lastProbeNanos = now;
            probe();
        }
        return available;
    }

    private void probe() {
        try {
            available = "PONG".equalsIgnoreCase(jedis.ping());
        } catch (RuntimeException e) {
            available = false;
        }
    }

    // ── Data operations (no-ops / nulls when unavailable) ───────────────

    public void set(String key, String value, int ttlSeconds) {
        if (!isAvailable()) {
            return;
        }
        try {
            jedis.set(key, value, SetParams.setParams().ex(ttlSeconds));
        } catch (RuntimeException e) {
            available = false;
        }
    }

    public String get(String key) {
        if (!isAvailable()) {
            return null;
        }
        try {
            return jedis.get(key);
        } catch (RuntimeException e) {
            available = false;
            return null;
        }
    }

    /** Atomic GET+DEL — single-use token consumption. */
    public String getAndDelete(String key) {
        if (!isAvailable()) {
            return null;
        }
        try {
            return jedis.getDel(key);
        } catch (RuntimeException e) {
            available = false;
            return null;
        }
    }

    public void delete(String key) {
        if (!isAvailable()) {
            return;
        }
        try {
            jedis.del(key);
        } catch (RuntimeException e) {
            available = false;
        }
    }

    public boolean exists(String key) {
        if (!isAvailable()) {
            return false;
        }
        try {
            return jedis.exists(key);
        } catch (RuntimeException e) {
            available = false;
            return false;
        }
    }

    public void increment(String key) {
        incrementWithTtl(key, 0);
    }

    /**
     * INCR; on the first increment of a key applies {@code ttlSeconds}
     * (the rate-limit window). Returns -1 when Redis is unavailable so
     * callers can fall back.
     */
    public long incrementWithTtl(String key, int ttlSeconds) {
        if (!isAvailable()) {
            return -1;
        }
        try {
            long count = jedis.incr(key);
            if (count == 1 && ttlSeconds > 0) {
                jedis.expire(key, ttlSeconds);
            }
            return count;
        } catch (RuntimeException e) {
            available = false;
            return -1;
        }
    }

    public long getCounter(String key) {
        String value = get(key);
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void addToSet(String key, String member) {
        if (!isAvailable()) {
            return;
        }
        try {
            jedis.sadd(key, member);
        } catch (RuntimeException e) {
            available = false;
        }
    }

    public java.util.Set<String> setMembers(String key) {
        if (!isAvailable()) {
            return java.util.Set.of();
        }
        try {
            return jedis.smembers(key);
        } catch (RuntimeException e) {
            available = false;
            return java.util.Set.of();
        }
    }

    // ── Pub/Sub (cross-instance WebSocket fan-out) ───────────────────────

    public void publish(String channel, String message) {
        if (!isAvailable()) {
            return;
        }
        try {
            jedis.publish(channel, message);
        } catch (RuntimeException e) {
            available = false;
        }
    }

    public void subscribe(String channel, Consumer<String> handler) {
        subscribePattern(channel, (ch, msg) -> handler.accept(msg));
    }

    /** Pattern subscription on a daemon virtual thread; reconnects forever. */
    public void subscribePattern(String pattern, BiConsumer<String, String> handler) {
        if (jedis == null) {
            return;
        }
        Thread.ofVirtual().name("redis-sub-" + pattern).start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    jedis.psubscribe(new JedisPubSub() {
                        @Override
                        public void onPMessage(String p, String channel, String message) {
                            handler.accept(channel, message);
                        }
                    }, pattern);
                } catch (RuntimeException e) {
                    // Redis down: back off, then retry so pub/sub self-heals.
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        });
    }

    @Override
    public void close() {
        if (jedis != null) {
            jedis.close();
        }
    }
}
