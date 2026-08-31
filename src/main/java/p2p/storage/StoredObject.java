package p2p.storage;

/**
 * Metadata of one object held by a {@link StorageProvider}. The id is
 * provider-scoped and opaque to callers; the SHA-256 lets the transfer
 * engine verify integrity end-to-end exactly as it does for live shares.
 */
public record StoredObject(
        String objectId,
        String fileName,
        long size,
        String sha256Hex,
        long createdAtEpochMs) {
}
