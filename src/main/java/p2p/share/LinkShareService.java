package p2p.share;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import p2p.auth.AuthContext;
import p2p.auth.PasswordHasher;
import p2p.engine.TransferEngine;
import p2p.plan.Entitlements;
import p2p.plan.PlanLimitException;
import p2p.plan.PlanService;
import p2p.security.FileSafetyService;
import p2p.user.TransferHistoryService;
import p2p.user.User;
import p2p.user.UserRepository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MODE 4 — Link Share (login required, cloud-storage backed). Upload goes
 * through the engine's storage layer; downloads stream from storage with the
 * same zero-copy discipline as live transfers.
 *
 * <p>This is also the service-layer enforcement point for plan entitlements
 * (deliverable: Free vs Premium logic lives HERE, not in the API layer):
 * max file size, storage quota, active-link count, expiry options, password
 * protection, download limits, and revocation are all checked against
 * {@link PlanService#entitlementsFor} before any byte is stored.
 */
public final class LinkShareService implements AutoCloseable {

    /** Creation options; null/0 mean "plan default". */
    public record LinkOptions(Duration ttl, String password, long maxDownloads) {

        public static final LinkOptions DEFAULTS = new LinkOptions(null, null, 0);
    }

    private static final char[] SLUG_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final int SLUG_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TransferEngine engine;
    private final TransferHistoryService history;
    private final PlanService plans;
    private final FileSafetyService fileSafety;
    private final UserRepository users;
    private final ObjectMapper json = new ObjectMapper();
    private final Path persistFile;
    private final ConcurrentHashMap<String, ShareLink> linksBySlug = new ConcurrentHashMap<>();
    private final Thread sweeper;

    public LinkShareService(TransferEngine engine, TransferHistoryService history,
                            PlanService plans, FileSafetyService fileSafety,
                            UserRepository users, Path dataDir) {
        this.engine = engine;
        this.history = history;
        this.plans = plans;
        this.fileSafety = fileSafety;
        this.users = users;
        this.persistFile = dataDir.resolve("links.json");
        load();
        // Virtual thread: sweeping expired links costs nothing while idle.
        this.sweeper = Thread.ofVirtual().name("link-expiry-sweeper").start(this::sweepLoop);
    }

    /**
     * Streams an upload into storage and mints the link, enforcing every
     * plan entitlement first. {@code declaredSize} is the spooled upload's
     * actual byte count (the API layer measures it before calling).
     */
    public ShareLink create(AuthContext owner, InputStream fileStream, String fileName,
                            long declaredSize, LinkOptions options) throws IOException {
        Entitlements plan = plans.entitlementsFor(owner.userId());
        enforceCreate(owner, fileName, declaredSize, options, plan);

        var object = engine.storage().put(fileStream, fileName);
        ShareLink link = new ShareLink(newSlug(), object.objectId(), owner.userId(),
                object.fileName(), object.size(), System.currentTimeMillis(),
                expiryFor(options.ttl(), plan), 0,
                options.password() == null || options.password().isBlank()
                        ? null : PasswordHasher.hash(options.password().toCharArray()),
                options.maxDownloads(), false);
        linksBySlug.put(link.slug(), link);
        persist();
        // Storage accounting on the user row (users.storage_used).
        users.incrementStorageUsed(owner.userId(), object.size());
        history.record(owner.userId(), "SEND", TransferHistoryService.Mode.LINK,
                link.fileName(), link.sizeBytes(), "link:" + link.slug(), "CREATED");
        return link;
    }

    private void enforceCreate(AuthContext owner, String fileName, long size,
                               LinkOptions options, Entitlements plan) throws IOException {
        FileSafetyService.Verdict verdict = fileSafety.check(fileName);
        if (!verdict.allowed()) {
            throw new PlanLimitException("file_blocked", verdict.reason());
        }
        if (size > plan.maxFileBytes()) {
            throw new PlanLimitException("max_file_size", "File exceeds the "
                    + (plan.maxFileBytes() >> 30) + " GB per-file limit of the "
                    + plan.tierName() + " plan");
        }
        // Unlimited (Integer.MAX_VALUE) skips the count entirely.
        if (plan.maxActiveLinks() != Integer.MAX_VALUE
                && activeLinksFor(owner.userId()).size() >= plan.maxActiveLinks()) {
            throw new PlanLimitException("active_links", "Active link limit reached ("
                    + plan.maxActiveLinks() + " on the " + plan.tierName() + " plan)");
        }
        // Quota is read off the user row (users.storage_used), not recomputed.
        long used = users.findById(owner.userId())
                .map(User::storageUsedBytes).orElse(0L);
        plans.checkStorageLimit(owner.userId(), used, size);
        if (options.password() != null && !options.password().isBlank()
                && !plan.passwordProtectedLinks()) {
            throw new PlanLimitException("password_links",
                    "Password-protected links require Premium");
        }
        if (options.maxDownloads() > 0 && !plan.downloadLimits()) {
            throw new PlanLimitException("download_limits",
                    "Download limits require Premium");
        }
        if (options.ttl() != null && !plan.customExpiryAllowed()
                && !plan.linkExpiryDayOptions().contains((int) options.ttl().toDays())) {
            throw new PlanLimitException("link_expiry", "The " + plan.tierName()
                    + " plan only allows expiry of " + plan.linkExpiryDayOptions() + " day(s)");
        }
    }

    private static long expiryFor(Duration requested, Entitlements plan) {
        if (requested == null) {
            // Plan default: smallest selectable option (FREE → 2 days).
            int days = plan.linkExpiryDayOptions().stream().min(Integer::compare).orElse(2);
            return System.currentTimeMillis() + Duration.ofDays(days).toMillis();
        }
        if (requested.isZero() || requested.isNegative()) { // 0 = never expire
            if (!plan.neverExpireAllowed()) {
                // Callers were already validated; belt-and-braces clamp.
                return System.currentTimeMillis() + plan.maxLinkTtl().toMillis();
            }
            return Long.MAX_VALUE;
        }
        Duration clamped = requested.compareTo(plan.maxLinkTtl()) > 0
                ? plan.maxLinkTtl() : requested;
        return System.currentTimeMillis() + clamped.toMillis();
    }

    /** Live link lookup for the public download endpoint. */
    public Optional<ShareLink> resolve(String slug) {
        ShareLink link = linksBySlug.get(slug);
        return link == null || link.expired() || link.revoked() || link.downloadsExhausted()
                ? Optional.empty() : Optional.of(link);
    }

    /** Constant-time password check for protected links. */
    public boolean passwordMatches(ShareLink link, String presented) {
        if (!link.passwordProtected()) {
            return true;
        }
        return presented != null
                && PasswordHasher.verify(presented.toCharArray(), link.passwordHash());
    }

    public void recordDownload(String slug) {
        linksBySlug.computeIfPresent(slug, (s, link) ->
                link.withDownloadCount(link.downloadCount() + 1));
        persist();
    }

    public List<ShareLink> listFor(AuthContext owner) {
        return linksBySlug.values().stream()
                .filter(l -> l.ownerUserId().equals(owner.userId()))
                .sorted(Comparator.comparingLong(ShareLink::createdAtEpochMs).reversed())
                .toList();
    }

    /** Storage bytes counted against {@code userId}'s quota right now. */
    public long storageUsed(String userId) {
        return activeLinksFor(userId).stream().mapToLong(ShareLink::sizeBytes).sum();
    }

    /** Platform-wide stored bytes (fylo_storage_used_bytes gauge). */
    public long totalStorageBytes() {
        return linksBySlug.values().stream().mapToLong(ShareLink::sizeBytes).sum();
    }

    public int activeLinkCount(String userId) {
        return activeLinksFor(userId).size();
    }

    /** Premium: kill the link but keep the record (and its analytics). */
    public boolean revoke(AuthContext owner, String slug) throws IOException {
        Entitlements plan = plans.entitlementsFor(owner.userId());
        if (!plan.linkRevocation()) {
            throw new PlanLimitException("link_revocation", "Link revocation requires Premium");
        }
        ShareLink link = linksBySlug.get(slug);
        if (link == null || !link.ownerUserId().equals(owner.userId())) {
            return false;
        }
        linksBySlug.put(slug, link.asRevoked());
        persist();
        return true;
    }

    public boolean delete(AuthContext owner, String slug) throws IOException {
        ShareLink link = linksBySlug.get(slug);
        if (link == null || !link.ownerUserId().equals(owner.userId())) {
            return false;
        }
        linksBySlug.remove(slug);
        engine.storage().delete(link.objectId());
        users.incrementStorageUsed(link.ownerUserId(), -link.sizeBytes());
        persist();
        return true;
    }

    private List<ShareLink> activeLinksFor(String userId) {
        return linksBySlug.values().stream()
                .filter(l -> l.ownerUserId().equals(userId)
                        && !l.expired() && !l.revoked())
                .toList();
    }

    private void sweepLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException e) {
                return;
            }
            List<ShareLink> expired = linksBySlug.values().stream()
                    .filter(ShareLink::expired).toList();
            for (ShareLink link : expired) {
                linksBySlug.remove(link.slug());
                try {
                    engine.storage().delete(link.objectId());
                    users.incrementStorageUsed(link.ownerUserId(), -link.sizeBytes());
                } catch (IOException e) {
                    System.err.println("Could not delete expired object "
                            + link.objectId() + ": " + e.getMessage());
                }
            }
            if (!expired.isEmpty()) {
                persist();
            }
        }
    }

    private String newSlug() {
        while (true) {
            StringBuilder slug = new StringBuilder(SLUG_LENGTH);
            for (int i = 0; i < SLUG_LENGTH; i++) {
                slug.append(SLUG_ALPHABET[RANDOM.nextInt(SLUG_ALPHABET.length)]);
            }
            if (!linksBySlug.containsKey(slug.toString())) {
                return slug.toString();
            }
        }
    }

    private void load() {
        if (!Files.exists(persistFile)) {
            return;
        }
        try {
            List<ShareLink> links = json.readValue(Files.readAllBytes(persistFile),
                    new TypeReference<>() {
                    });
            links.forEach(l -> linksBySlug.put(l.slug(), l));
        } catch (IOException e) {
            System.err.println("Could not load share links: " + e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            Files.createDirectories(persistFile.getParent());
            Path tmp = persistFile.resolveSibling(persistFile.getFileName() + ".tmp");
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(tmp.toFile(), List.copyOf(linksBySlug.values()));
            Files.move(tmp, persistFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Could not persist share links: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        sweeper.interrupt();
    }
}
