package p2p.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The ONE storage abstraction of the platform. Link Share (mode 4) is the
 * only mode that persists bytes; it does so exclusively through this
 * interface so the backend can move from local disk to MinIO/S3 without
 * touching the transfer engine or any mode service.
 *
 * <p>Implementations must stream — no method may buffer a whole file in
 * memory.
 */
public interface StorageProvider {

    /**
     * Streams {@code in} to durable storage and returns the object metadata.
     * The SHA-256 is computed while writing (single pass, constant memory).
     */
    StoredObject put(InputStream in, String fileName) throws IOException;

    /** Metadata lookup; empty when the object does not exist. */
    Optional<StoredObject> stat(String objectId) throws IOException;

    /**
     * Opens the object for zero-copy reads ({@code FileChannel.transferTo}).
     * Providers that are not path-backed (S3) return {@link Optional#empty()},
     * and callers fall back to {@link #open(String)}.
     */
    Optional<FileChannel> openChannel(String objectId) throws IOException;

    /** Plain streaming read from {@code offset}; works for every provider. */
    InputStream open(String objectId, long offset) throws IOException;

    /**
     * Path of the object if it lives on the local filesystem — this lets the
     * transfer engine serve a stored object through the same {@code FileSender}
     * zero-copy path used for live shares. Empty for remote providers.
     */
    Optional<Path> localPath(String objectId);

    boolean delete(String objectId) throws IOException;

    /** All objects currently stored (for sweeps and admin views). */
    List<StoredObject> list() throws IOException;
}
