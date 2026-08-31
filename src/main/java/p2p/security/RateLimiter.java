package p2p.security;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process token-bucket rate limiter, keyed by (client, policy). Buckets
 * refill continuously; a request that finds an empty bucket is rejected
 * (the API layer answers 429 with Retry-After).
 *
 * <p>Policies are deliberately coarse — four classes cover the whole API:
 * <ul>
 *   <li>{@code AUTH} — credential guessing is the threat: 10/min</li>
 *   <li>{@code API} — general JSON endpoints: 120/min</li>
 *   <li>{@code DOWNLOAD} — public link + gateway downloads: 60/min</li>
 *   <li>{@code UPLOAD} — expensive writes: 20/min</li>
 * </ul>
 *
 * <p>Single-node scope by design: with N replicas behind a load balancer the
 * effective limit is ≤ N× these numbers, which is acceptable for abuse
 * control; strict global limits move to the edge (nginx {@code limit_req})
 * or a Redis bucket — see docs/UNIFIED-PLATFORM-PART2.md §Security.
 */
public final class RateLimiter {

    public enum Policy {
        AUTH(10, 60_000),
        API(120, 60_000),
        DOWNLOAD(60, 60_000),
        UPLOAD(20, 60_000);

        final long capacity;
        final long windowMs;

        Policy(long capacity, long windowMs) {
            this.capacity = capacity;
            this.windowMs = windowMs;
        }

        public long retryAfterSeconds() {
            return Math.max(1, windowMs / 1000 / capacity);
        }
    }

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
        }
    }

    private static final int MAX_TRACKED_CLIENTS = 100_000;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final p2p.infra.RedisClient redis; // null = in-memory only

    public RateLimiter() {
        this(null);
    }

    /** Redis-backed when available; transparent in-memory fallback. */
    public RateLimiter(p2p.infra.RedisClient redis) {
        this.redis = redis;
    }

    /** True if the request may proceed; false → reject with 429. */
    public boolean tryAcquire(String clientKey, Policy policy) {
        // Redis-backed fixed window: shared across instances (backbone §12.2).
        if (redis != null && redis.isAvailable()) {
            long count = redis.incrementWithTtl(
                    "rate:" + policy.name() + ":" + clientKey,
                    (int) (policy.windowMs / 1000));
            if (count > 0) {
                return count <= policy.capacity;
            }
            // count <= 0 → Redis dropped mid-request; fall through to local.
        }
        return tryAcquireLocal(clientKey, policy);
    }

    private boolean tryAcquireLocal(String clientKey, Policy policy) {
        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            buckets.clear(); // crude memory backstop; resets all windows
        }
        long now = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(policy.name() + "|" + clientKey,
                k -> new Bucket(policy.capacity, now));
        synchronized (bucket) {
            double refillPerNano = policy.capacity / (policy.windowMs * 1_000_000.0);
            bucket.tokens = Math.min(policy.capacity,
                    bucket.tokens + (now - bucket.lastRefillNanos) * refillPerNano);
            bucket.lastRefillNanos = now;
            if (bucket.tokens < 1.0) {
                return false;
            }
            bucket.tokens -= 1.0;
            return true;
        }
    }
}
