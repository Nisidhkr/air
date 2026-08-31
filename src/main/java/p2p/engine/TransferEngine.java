package p2p.engine;

import p2p.service.FileSharer;
import p2p.storage.StorageProvider;
import p2p.storage.StoredObject;
import p2p.transfer.TransferConfig;
import p2p.transfer.TransferManager;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * THE unified transfer engine. Every sharing mode — Direct, Nearby, Username,
 * Link — moves bytes exclusively through this facade; no mode may construct a
 * {@code FileSender}/{@code FileReceiver} or touch storage directly.
 *
 * <p>The engine composes the components that already power PeerLink:
 * <ul>
 *   <li>{@link FileSharer} — the send side: one zero-copy
 *       ({@code FileChannel.transferTo}/sendfile) {@code FileSender} per
 *       offered source, N concurrent receivers, per-transfer bearer token.</li>
 *   <li>{@link TransferManager} — the receive side and the queue: bounded
 *       concurrency, pause/resume/cancel, exact-byte resume, SHA-256 whole-file
 *       verification with CRC32C chunk repair, progress/ETA snapshots.</li>
 *   <li>{@link StorageProvider} — durable objects for Link Share; a stored
 *       object is served through the very same {@code FileSender} path when it
 *       is local, so there is exactly one wire implementation.</li>
 * </ul>
 *
 * <p>Memory model: no file bytes on the heap, ever. Sends are kernel
 * zero-copy; receives run through fixed-size direct buffers. A 40 GB transfer
 * stays under 100 MB of RAM regardless of concurrency.
 */
public final class TransferEngine implements Closeable {

    /** A live share: connect to {@code port} with {@code token} to pull the bytes. */
    public record Share(int port, String token, String fileName, long size, ShareCodes.Code code) {
    }

    /** Max bytes per transferTo call (keeps slow clients observable). */
    private static final long STORED_SLICE_BYTES = 8L << 20;
    private static final int COPY_BUFFER_BYTES = 256 * 1024;

    private final FileSharer fileSharer;
    private final TransferManager transferManager;
    private final StorageProvider storage;

    public TransferEngine(FileSharer fileSharer, TransferManager transferManager,
                          StorageProvider storage) {
        this.fileSharer = fileSharer;
        this.transferManager = transferManager;
        this.storage = storage;
    }

    // ── Send side ────────────────────────────────────────────────────────

    /** Offers a source for pulling; returns the port/token/share-code triple. */
    public Share offer(TransferSource source) throws IOException {
        FileSharer.Offer offer = switch (source) {
            case TransferSource.LiveFile live -> fileSharer.offer(live.path(), live.relativePath());
            case TransferSource.Stored stored -> fileSharer.offer(stored.localPath(), null);
        };
        return new Share(offer.port(), offer.token(), source.displayName(), source.size(),
                ShareCodes.of(offer.port(), offer.token()));
    }

    /** Wraps a stored object so it can be offered like any live file. */
    public TransferSource.Stored storedSource(StoredObject object) throws IOException {
        Path path = storage.localPath(object.objectId())
                .orElseThrow(() -> new IOException(
                        "Object " + object.objectId() + " is not locally materialized"));
        return new TransferSource.Stored(object, path);
    }

    public Optional<FileSharer.ShareInfo> shareInfo(int port) {
        return fileSharer.shareInfo(port);
    }

    public void stopShare(int port) {
        fileSharer.stopSharing(port);
    }

    // ── Receive side / queue ─────────────────────────────────────────────

    /** Queues a pull from a remote share; the queue applies backpressure. */
    public TransferManager.Transfer enqueueReceive(TransferManager.ReceiveSpec spec, long totalBytes,
                                                   String peerName) {
        return transferManager.enqueueReceive(spec, totalBytes, peerName, null);
    }

    public boolean pause(String transferId) {
        return transferManager.pause(transferId);
    }

    public boolean resume(String transferId) {
        return transferManager.resume(transferId);
    }

    public boolean cancel(String transferId) {
        return transferManager.cancel(transferId);
    }

    public List<TransferManager.TransferView> transfers() {
        return transferManager.snapshots();
    }

    // ── Storage side (Link Share) ────────────────────────────────────────

    public StorageProvider storage() {
        return storage;
    }

    /**
     * Streams {@code length} bytes of a stored object from {@code offset}
     * into {@code out} (HTTP Range support for Link Share). Provider-aware:
     * path-backed storage goes zero-copy via {@code FileChannel.transferTo};
     * remote providers (MinIO/S3) stream a ranged GET through a fixed buffer.
     * Either way: constant memory, one code path for all deployments.
     */
    public void streamStored(String objectId, long offset, long length, OutputStream out)
            throws IOException {
        Optional<FileChannel> local = storage.openChannel(objectId);
        if (local.isPresent()) {
            try (FileChannel channel = local.get()) {
                WritableByteChannel sink = Channels.newChannel(out);
                long position = offset;
                long remaining = length;
                while (remaining > 0) {
                    long sent = channel.transferTo(position,
                            Math.min(remaining, STORED_SLICE_BYTES), sink);
                    if (sent <= 0) {
                        throw new IOException("Stored object truncated: " + objectId);
                    }
                    position += sent;
                    remaining -= sent;
                }
            }
            return;
        }
        try (InputStream in = storage.open(objectId, offset)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            long remaining = length;
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IOException("Stored object truncated: " + objectId);
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    @Override
    public void close() {
        transferManager.close();
        fileSharer.close();
    }
}
