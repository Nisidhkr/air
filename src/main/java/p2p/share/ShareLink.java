package p2p.share;

/**
 * MODE 4 — a cloud share link: {@code https://fylo.app/s/<slug>}. The file
 * lives in the storage layer, so it stays downloadable after the sender
 * leaves — the defining difference from the three live modes.
 *
 * <p>Premium fields: {@code passwordHash} (protected links),
 * {@code maxDownloads} (0 = unlimited), {@code revoked} (kill switch that
 * keeps the record for analytics). All are enforced by
 * {@link LinkShareService} against the owner's {@link p2p.plan.Entitlements}.
 */
public record ShareLink(
        String slug,
        String objectId,
        String ownerUserId,
        String fileName,
        long sizeBytes,
        long createdAtEpochMs,
        long expiresAtEpochMs,   // Long.MAX_VALUE = never expires (premium)
        long downloadCount,
        String passwordHash,     // null = public
        long maxDownloads,       // 0 = unlimited
        boolean revoked) {

    public boolean expired() {
        return System.currentTimeMillis() > expiresAtEpochMs;
    }

    public boolean passwordProtected() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public boolean downloadsExhausted() {
        return maxDownloads > 0 && downloadCount >= maxDownloads;
    }

    public ShareLink withDownloadCount(long count) {
        return new ShareLink(slug, objectId, ownerUserId, fileName, sizeBytes,
                createdAtEpochMs, expiresAtEpochMs, count, passwordHash, maxDownloads, revoked);
    }

    public ShareLink asRevoked() {
        return new ShareLink(slug, objectId, ownerUserId, fileName, sizeBytes,
                createdAtEpochMs, expiresAtEpochMs, downloadCount, passwordHash, maxDownloads, true);
    }
}
