package p2p.engine;

import p2p.storage.StoredObject;

import java.nio.file.Path;

/**
 * What a share serves. Every mode reduces to one of these two cases, which is
 * why one engine can power all four:
 *
 * <ul>
 *   <li>{@link LiveFile} — bytes come straight off the sender's disk while the
 *       sender is online (Direct Share, Nearby Share, Username Share).</li>
 *   <li>{@link Stored} — bytes were persisted through the storage layer and
 *       survive the sender leaving (Link Share).</li>
 * </ul>
 */
public sealed interface TransferSource {

    /** Filename shown to the receiver. */
    String displayName();

    long size();

    record LiveFile(Path path, String displayName, long size, String relativePath)
            implements TransferSource {
    }

    record Stored(StoredObject object, Path localPath) implements TransferSource {
        @Override
        public String displayName() {
            return object.fileName();
        }

        @Override
        public long size() {
            return object.size();
        }
    }
}
