package p2p.transfer;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live state of in-flight uploads, keyed by uploadId, feeding the
 * {@code GET /api/v1/uploads/{id}/progress} SSE stream. Entries linger for
 * a few minutes after a terminal state so a client that connects late still
 * sees COMPLETED/FAILED instead of a 404.
 */
public final class UploadProgressRegistry {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    /** Mutable per-upload state; bytes is updated from the ingress loop. */
    public static final class UploadProgress {
        public final String uploadId;
        public final AtomicLong bytesTransferred = new AtomicLong();
        public final long totalBytes;          // ≤0 = unknown length
        public final long startTimeMs = System.currentTimeMillis();
        public volatile String status = IN_PROGRESS;
        volatile long terminalAtMs;

        UploadProgress(String uploadId, long totalBytes) {
            this.uploadId = uploadId;
            this.totalBytes = totalBytes;
        }
    }

    private static final long RETENTION_MS = 5 * 60_000;

    private final ConcurrentHashMap<String, UploadProgress> byId = new ConcurrentHashMap<>();

    public UploadProgress register(String uploadId, long totalBytes) {
        sweep();
        UploadProgress progress = new UploadProgress(uploadId, totalBytes);
        byId.put(uploadId, progress);
        return progress;
    }

    public Optional<UploadProgress> find(String uploadId) {
        return Optional.ofNullable(byId.get(uploadId));
    }

    public void finish(String uploadId, boolean success) {
        UploadProgress progress = byId.get(uploadId);
        if (progress != null) {
            progress.status = success ? COMPLETED : FAILED;
            progress.terminalAtMs = System.currentTimeMillis();
        }
    }

    private void sweep() {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        byId.values().removeIf(p -> !IN_PROGRESS.equals(p.status)
                && p.terminalAtMs < cutoff);
    }
}
