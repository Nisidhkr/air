package p2p.transfer;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * Owns every transfer this node is part of — LAN receives, and the
 * control-plane status of LAN sends — as a queue with a bounded number of
 * simultaneously active transfers. Both transfer modes share this: a queued
 * receive is just a {@link FileReceiver} run, so it gets parallel segments,
 * resume, verification, and retries for free.
 *
 * <p><b>Pause</b> aborts the underlying receiver (resume metadata stays on
 * disk) and parks the task; <b>resume</b> re-enqueues it, continuing from the
 * exact byte offset. <b>Cancel</b> additionally deletes the partial file and
 * resume metadata.
 */
public final class TransferManager implements Closeable {

    public enum Direction { SEND, RECEIVE }

    public enum Status { QUEUED, ACTIVE, PAUSED, COMPLETED, FAILED, CANCELLED, AWAITING_APPROVAL, REJECTED }

    /** Connection details needed to (re)start a receive. */
    public record ReceiveSpec(String host, int port, String token, Path targetDirectory, String fileName) {
    }

    /** JSON-friendly snapshot for the UI. */
    public record TransferView(
            String id, String direction, String status, String fileName, String peerName,
            long totalBytes, long transferredBytes, double percent, double mbPerSec,
            long etaSeconds, String error, long createdAtEpochMs) {
    }

    public static final class Transfer {
        private final String id = UUID.randomUUID().toString();
        private final Direction direction;
        private final String peerName;
        private final String fileName;
        private final long totalBytes;
        private final long createdAtEpochMs = System.currentTimeMillis();
        private final ReceiveSpec spec; // null for SEND-direction tracking
        private final Consumer<Transfer> onTerminal; // may be null

        private volatile Status status;
        private volatile String error;
        private volatile ProgressTracker.Snapshot lastProgress;
        private volatile FileReceiver activeReceiver;
        private volatile boolean pauseRequested;
        private volatile boolean cancelRequested;

        private Transfer(Direction direction, String peerName, String fileName, long totalBytes,
                         ReceiveSpec spec, Status initialStatus, Consumer<Transfer> onTerminal) {
            this.direction = direction;
            this.peerName = peerName;
            this.fileName = fileName;
            this.totalBytes = totalBytes;
            this.spec = spec;
            this.status = initialStatus;
            this.onTerminal = onTerminal;
        }

        public String id() {
            return id;
        }

        public Status status() {
            return status;
        }

        public String fileName() {
            return fileName;
        }

        TransferView view() {
            ProgressTracker.Snapshot snap = lastProgress;
            long transferred = snap != null ? snap.transferredBytes()
                    : (status == Status.COMPLETED ? totalBytes : 0);
            double percent = totalBytes == 0 ? 100.0 : 100.0 * transferred / totalBytes;
            return new TransferView(id, direction.name(), status.name(), fileName, peerName,
                    totalBytes, transferred, percent,
                    snap != null && status == Status.ACTIVE ? snap.megabytesPerSecond() : 0,
                    snap != null && status == Status.ACTIVE ? snap.etaSeconds() : -1,
                    error, createdAtEpochMs);
        }
    }

    private static final int MAX_HISTORY = 200;
    private static final long PROGRESS_EVENT_INTERVAL_MS = 2_000; // backbone §6.4

    private final ConcurrentHashMap<String, Transfer> transfers = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore activeSlots;

    // Real-time + observability hooks (backbone §6.4, §17); optional so the
    // engine stays usable in tests and headless embeddings.
    private volatile p2p.ws.WebSocketNotifier notifier;
    private volatile p2p.observability.Metrics metrics;

    public TransferManager(int maxConcurrentTransfers) {
        this.activeSlots = new Semaphore(maxConcurrentTransfers);
    }

    public void setNotifier(p2p.ws.WebSocketNotifier notifier) {
        this.notifier = notifier;
    }

    public void setMetrics(p2p.observability.Metrics metrics) {
        this.metrics = metrics;
    }

    /** Queues a receive; starts as soon as a slot is free. */
    public Transfer enqueueReceive(ReceiveSpec spec, long totalBytes, String peerName,
                                   Consumer<Transfer> onTerminal) {
        Transfer transfer = new Transfer(Direction.RECEIVE, peerName, spec.fileName(), totalBytes,
                spec, Status.QUEUED, onTerminal);
        register(transfer);
        executor.submit(() -> runReceive(transfer));
        return transfer;
    }

    /** Tracks an outgoing LAN send (data is pulled by the peer; status is control-plane only). */
    public Transfer trackOutgoingSend(String fileName, long totalBytes, String peerName) {
        Transfer transfer = new Transfer(Direction.SEND, peerName, fileName, totalBytes,
                null, Status.AWAITING_APPROVAL, null);
        register(transfer);
        return transfer;
    }

    public void updateStatus(String transferId, Status status) {
        Transfer transfer = transfers.get(transferId);
        if (transfer != null) {
            transfer.status = status;
        }
    }

    public boolean pause(String transferId) {
        Transfer transfer = transfers.get(transferId);
        if (transfer == null || transfer.direction != Direction.RECEIVE
                || (transfer.status != Status.ACTIVE && transfer.status != Status.QUEUED)) {
            return false;
        }
        transfer.pauseRequested = true;
        FileReceiver receiver = transfer.activeReceiver;
        if (receiver != null) {
            receiver.abort();
        } else if (transfer.status == Status.QUEUED) {
            transfer.status = Status.PAUSED; // runReceive will park when it dequeues
        }
        return true;
    }

    public boolean resume(String transferId) {
        Transfer transfer = transfers.get(transferId);
        if (transfer == null || transfer.spec == null
                || (transfer.status != Status.PAUSED && transfer.status != Status.FAILED)) {
            return false;
        }
        transfer.pauseRequested = false;
        transfer.cancelRequested = false;
        transfer.error = null;
        transfer.status = Status.QUEUED;
        executor.submit(() -> runReceive(transfer));
        return true;
    }

    public boolean cancel(String transferId) {
        Transfer transfer = transfers.get(transferId);
        if (transfer == null) {
            return false;
        }
        transfer.cancelRequested = true;
        FileReceiver receiver = transfer.activeReceiver;
        if (receiver != null) {
            receiver.abort();
        } else if (transfer.status == Status.QUEUED || transfer.status == Status.PAUSED) {
            transfer.status = Status.CANCELLED;
            cleanupPartials(transfer);
        }
        return true;
    }

    public List<TransferView> snapshots() {
        return transfers.values().stream()
                .sorted(Comparator.comparingLong((Transfer t) -> t.createdAtEpochMs).reversed())
                .map(Transfer::view)
                .toList();
    }

    private void register(Transfer transfer) {
        transfers.put(transfer.id, transfer);
        pruneHistory();
    }

    private void runReceive(Transfer transfer) {
        try {
            activeSlots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (transfer.cancelRequested) {
                transfer.status = Status.CANCELLED;
                return;
            }
            if (transfer.pauseRequested || transfer.status == Status.PAUSED) {
                transfer.status = Status.PAUSED;
                return;
            }
            transfer.status = Status.ACTIVE;
            FileReceiver receiver = new FileReceiver(
                    transfer.spec.host(), transfer.spec.port(), transfer.spec.token(),
                    transfer.spec.targetDirectory(), TransferConfig.lan());
            transfer.activeReceiver = receiver;
            // Throttled progress fan-out: WS event + byte counter every ~2 s.
            long[] lastEvent = {System.currentTimeMillis()};
            long[] lastBytes = {0};
            receiver.download(snapshot -> {
                transfer.lastProgress = snapshot;
                long now = System.currentTimeMillis();
                if (now - lastEvent[0] >= PROGRESS_EVENT_INTERVAL_MS) {
                    lastEvent[0] = now;
                    if (metrics != null) {
                        metrics.add("fylo_transfer_bytes_total",
                                snapshot.transferredBytes() - lastBytes[0]);
                    }
                    lastBytes[0] = snapshot.transferredBytes();
                    if (notifier != null) {
                        notifier.sendProgress(transfer.id, snapshot.transferredBytes(),
                                transfer.totalBytes,
                                (long) (snapshot.megabytesPerSecond() * 1_000_000));
                    }
                }
            });
            transfer.status = Status.COMPLETED;
        } catch (IOException e) {
            if (transfer.pauseRequested) {
                transfer.status = Status.PAUSED;
            } else if (transfer.cancelRequested) {
                transfer.status = Status.CANCELLED;
                cleanupPartials(transfer);
            } else {
                transfer.status = Status.FAILED;
                transfer.error = e.getMessage();
            }
        } finally {
            transfer.activeReceiver = null;
            activeSlots.release();
            emitTerminal(transfer);
            if (transfer.onTerminal != null
                    && (transfer.status == Status.COMPLETED || transfer.status == Status.FAILED
                        || transfer.status == Status.CANCELLED)) {
                transfer.onTerminal.accept(transfer);
            }
        }
    }

    /** Terminal-state observability: WS TRANSFER_STATUS + metrics (§6.4, §17). */
    private void emitTerminal(Transfer transfer) {
        Status status = transfer.status;
        if (status != Status.COMPLETED && status != Status.FAILED
                && status != Status.CANCELLED) {
            return;
        }
        if (notifier != null) {
            notifier.sendStatus(transfer.id, status.name(), null);
        }
        if (metrics != null) {
            if (status == Status.FAILED) {
                metrics.increment("fylo_transfer_errors_total");
            }
            if (status == Status.COMPLETED) {
                metrics.observeDurationSeconds("fylo_transfer_duration_seconds",
                        (System.currentTimeMillis() - transfer.createdAtEpochMs) / 1000.0);
            }
        }
    }

    private void cleanupPartials(Transfer transfer) {
        if (transfer.spec == null) {
            return;
        }
        String safeName = FileReceiver.sanitizeFilename(transfer.spec.fileName());
        try {
            Files.deleteIfExists(transfer.spec.targetDirectory().resolve(safeName + ".part"));
            Files.deleteIfExists(transfer.spec.targetDirectory().resolve(safeName + ".resume"));
        } catch (IOException e) {
            System.err.println("Could not clean up partial files: " + e.getMessage());
        }
    }

    private void pruneHistory() {
        if (transfers.size() <= MAX_HISTORY) {
            return;
        }
        transfers.values().stream()
                .filter(t -> t.status == Status.COMPLETED || t.status == Status.FAILED
                        || t.status == Status.CANCELLED || t.status == Status.REJECTED)
                .sorted(Comparator.comparingLong(t -> t.createdAtEpochMs))
                .limit(Math.max(0, transfers.size() - MAX_HISTORY))
                .forEach(t -> transfers.remove(t.id));
    }

    @Override
    public void close() {
        transfers.values().forEach(t -> {
            FileReceiver receiver = t.activeReceiver;
            if (receiver != null) {
                receiver.abort();
            }
        });
        executor.shutdown();
    }
}
