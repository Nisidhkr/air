package p2p.transfer;

/**
 * Token-bucket upload throttle, one instance per upload session
 * (plan enforcement: FREE = 2 MB/s, PREMIUM = unlimited).
 *
 * <p>The bucket starts EMPTY and refills continuously at
 * {@code bytesPerSecond}, capped at one second of burst — so the cap holds
 * from the very first byte and a paused stream cannot bank more than one
 * second of credit.
 *
 * <p>{@link #acquire(long)} blocks with {@link Thread#sleep}, which parks
 * only the calling virtual thread — thousands of throttled uploads cost
 * nothing while waiting. The unlimited path (Long.MAX_VALUE) returns
 * immediately: zero overhead for PREMIUM.
 *
 * <p>Not thread-safe by design: an upload session is read by exactly one
 * virtual thread.
 */
public final class UploadThrottle {

    private final long bytesPerSecond;   // Long.MAX_VALUE = no limit
    private long tokens;                 // available bytes to send now
    private long lastRefillNanos;

    public UploadThrottle(long bytesPerSecond) {
        this.bytesPerSecond = bytesPerSecond;
        this.tokens = 0;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Call before accepting each chunk. Blocks until the quota allows
     * {@code chunkBytes}; never blocks on the unlimited tier.
     */
    public void acquire(long chunkBytes) throws InterruptedException {
        if (bytesPerSecond == Long.MAX_VALUE) {
            return; // unlimited, no-op fast path
        }
        refill();
        while (tokens < chunkBytes) {
            long deficit = chunkBytes - tokens;
            long waitNanos = (deficit * 1_000_000_000L) / bytesPerSecond;
            // Virtual-thread-friendly: parks this thread only.
            Thread.sleep(Math.max(1, waitNanos / 1_000_000),
                    (int) (waitNanos % 1_000_000));
            refill();
        }
        tokens -= chunkBytes;
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        long newTokens = (elapsed * bytesPerSecond) / 1_000_000_000L;
        if (newTokens > 0) {
            tokens = Math.min(bytesPerSecond, tokens + newTokens); // ≤ 1 s burst
            lastRefillNanos = now;
        }
    }

    public long getBytesPerSecond() {
        return bytesPerSecond;
    }
}
