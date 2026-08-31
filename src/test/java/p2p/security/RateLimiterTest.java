package p2p.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void allowsUpToCapacityThenRejects() {
        RateLimiter limiter = new RateLimiter();
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("1.2.3.4", RateLimiter.Policy.AUTH),
                    "request " + i + " should pass");
        }
        assertFalse(limiter.tryAcquire("1.2.3.4", RateLimiter.Policy.AUTH),
                "11th auth request within a minute must be rejected");
    }

    @Test
    void clientsAndPoliciesAreIsolated() {
        RateLimiter limiter = new RateLimiter();
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("1.2.3.4", RateLimiter.Policy.AUTH);
        }
        // Different client: fresh bucket.
        assertTrue(limiter.tryAcquire("5.6.7.8", RateLimiter.Policy.AUTH));
        // Same client, different policy: fresh bucket.
        assertTrue(limiter.tryAcquire("1.2.3.4", RateLimiter.Policy.API));
    }

    /** Simulated Redis: INCR-with-TTL counters, controllable availability. */
    private static final class FakeRedis extends p2p.infra.RedisClient {
        final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
                counters = new java.util.concurrent.ConcurrentHashMap<>();
        boolean available = true;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public long incrementWithTtl(String key, int ttlSeconds) {
            if (!available) {
                return -1;
            }
            return counters.computeIfAbsent(key,
                    k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        }
    }

    @Test
    void redisBackedCountersEnforceTheLimit() {
        FakeRedis redis = new FakeRedis();
        RateLimiter limiter = new RateLimiter(redis);
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("9.9.9.9", RateLimiter.Policy.AUTH));
        }
        assertFalse(limiter.tryAcquire("9.9.9.9", RateLimiter.Policy.AUTH),
                "11th request must be rejected by the shared counter");
        assertEquals(11, redis.counters.get("rate:AUTH:9.9.9.9").get(),
                "counter must live in Redis, not in-process");
    }

    @Test
    void fallsBackToInMemoryWhenRedisUnavailable() {
        FakeRedis redis = new FakeRedis();
        redis.available = false; // mock isAvailable() == false
        RateLimiter limiter = new RateLimiter(redis);
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("8.8.8.8", RateLimiter.Policy.AUTH));
        }
        assertFalse(limiter.tryAcquire("8.8.8.8", RateLimiter.Policy.AUTH),
                "in-memory fallback must still enforce the limit");
        assertTrue(redis.counters.isEmpty(), "Redis must not have been touched");
    }
}
