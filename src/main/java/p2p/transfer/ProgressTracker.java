package p2p.transfer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe transfer progress. Writers (segment threads) only touch an
 * {@link AtomicLong}; speed/ETA are computed lazily by whoever polls
 * {@link #snapshot()}, so the hot transfer path never takes a lock.
 *
 * <p>Speed is measured over a sliding window (~3 s) rather than since start,
 * so it reflects current conditions and gives a stable ETA after resume
 * (where "average since start" would be wildly wrong).
 */
public final class ProgressTracker {

    private static final long WINDOW_NANOS = 3_000_000_000L;

    private final long totalBytes;
    private final AtomicLong transferredBytes = new AtomicLong();

    // Window state, guarded by synchronized(this) in snapshot() only.
    private long windowStartNanos = System.nanoTime();
    private long windowStartBytes;
    private double lastWindowBytesPerSec;

    public ProgressTracker(long totalBytes, long alreadyTransferred) {
        this.totalBytes = totalBytes;
        this.transferredBytes.set(alreadyTransferred);
        this.windowStartBytes = alreadyTransferred;
    }

    /** Called from transfer threads; lock-free. */
    public void add(long bytes) {
        transferredBytes.addAndGet(bytes);
    }

    public long transferred() {
        return transferredBytes.get();
    }

    public long total() {
        return totalBytes;
    }

    public record Snapshot(long transferredBytes, long totalBytes, double percent,
                           double megabytesPerSecond, long etaSeconds) {

        @Override
        public String toString() {
            return String.format("%,d / %,d bytes (%.1f%%) at %.1f MB/s, ETA %ds",
                    transferredBytes, totalBytes, percent, megabytesPerSecond, etaSeconds);
        }
    }

    public synchronized Snapshot snapshot() {
        long now = System.nanoTime();
        long bytes = transferredBytes.get();
        long elapsed = now - windowStartNanos;
        if (elapsed >= WINDOW_NANOS) {
            lastWindowBytesPerSec = (bytes - windowStartBytes) * 1e9 / elapsed;
            windowStartNanos = now;
            windowStartBytes = bytes;
        } else if (lastWindowBytesPerSec == 0 && elapsed > 0) {
            // First window not complete yet: report the partial-window rate.
            lastWindowBytesPerSec = (bytes - windowStartBytes) * 1e9 / elapsed;
        }
        double percent = totalBytes == 0 ? 100.0 : 100.0 * bytes / totalBytes;
        long remaining = totalBytes - bytes;
        long etaSeconds = lastWindowBytesPerSec > 0
                ? (long) Math.ceil(remaining / lastWindowBytesPerSec)
                : -1;
        return new Snapshot(bytes, totalBytes, percent, lastWindowBytesPerSec / 1e6, etaSeconds);
    }
}
