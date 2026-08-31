package p2p.transfer;

import org.junit.jupiter.api.Test;
import p2p.plan.Entitlements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan-based upload throttling (FREE 2 MB/s, PREMIUM unlimited). */
class UploadThrottleTest {

    @Test
    void unlimitedThrottle_acquireIsInstant() throws InterruptedException {
        UploadThrottle throttle = new UploadThrottle(Long.MAX_VALUE);
        long start = System.nanoTime();
        throttle.acquire(100L * 1024 * 1024); // 100 MB
        long elapsed = System.nanoTime() - start;
        assertTrue(elapsed < 5_000_000L, "unlimited acquire must be instant, took "
                + elapsed / 1_000_000 + "ms");
    }

    @Test
    void throttledUpload_respectsRateLimit() throws InterruptedException {
        UploadThrottle throttle = new UploadThrottle(1024 * 1024); // 1 MB/s
        long start = System.nanoTime();
        throttle.acquire(512 * 1024); // 512 KB
        throttle.acquire(512 * 1024); // 512 KB → 1 MB total
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 900, "1 MB at 1 MB/s must take ~1s, took " + elapsedMs + "ms");
        assertTrue(elapsedMs <= 1500, "throttle overshot: " + elapsedMs + "ms");
    }

    @Test
    void freeEntitlement_hasThrottleOf2MBs() {
        assertEquals(2L * 1024 * 1024, Entitlements.FREE.uploadSpeedBytesPerSecond());
        assertTrue(Entitlements.FREE.isThrottled());
        assertEquals("2 MB/s", Entitlements.FREE.uploadSpeedLabel());
    }

    @Test
    void premiumEntitlement_hasNoThrottle() {
        Entitlements premium = Entitlements.premium(500L << 30);
        assertEquals(Long.MAX_VALUE, premium.uploadSpeedBytesPerSecond());
        assertFalse(premium.isThrottled());
        assertEquals("Unlimited", premium.uploadSpeedLabel());
    }
}
