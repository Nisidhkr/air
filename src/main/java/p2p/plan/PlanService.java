package p2p.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ONE authority on who is entitled to what. Everything that gates a
 * feature — link creation, upload size, history depth, device sync — asks
 * this service; nothing else stores plan state.
 *
 * <p>Subscriptions persist to {@code subscriptions.json} in Part 1 style;
 * in production this reads the {@code users.plan_tier / plan_expires_at}
 * columns and is fed by the billing webhook (Stripe et al.) — same API.
 */
public final class PlanService {

    /** An active premium subscription. */
    public record Subscription(String userId, long storageBytes, long expiresAtEpochMs) {

        public boolean active() {
            return expiresAtEpochMs == 0 // 0 = does not expire
                    || expiresAtEpochMs > System.currentTimeMillis();
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final Path persistFile;
    private final ConcurrentHashMap<String, Subscription> byUserId = new ConcurrentHashMap<>();

    public PlanService(Path dataDir) {
        this.persistFile = dataDir.resolve("subscriptions.json");
        load();
    }

    /** The single lookup every enforcement point uses. */
    public Entitlements entitlementsFor(String userId) {
        Subscription sub = byUserId.get(userId);
        return sub != null && sub.active()
                ? Entitlements.premium(sub.storageBytes())
                : Entitlements.FREE;
    }

    /**
     * Storage-quota gate (backbone §5.2): {@code usedBytes} comes off the
     * user row ({@code users.storage_used}, mirrored by
     * {@code User.storageUsedBytes}); the limit is the plan's
     * {@code maxStorageBytes} (mirrored by {@code users.storage_limit}).
     */
    public void checkStorageLimit(String userId, long usedBytes, long additionalBytes)
            throws PlanLimitException {
        Entitlements plan = entitlementsFor(userId);
        if (usedBytes + additionalBytes > plan.maxStorageBytes()) {
            throw new PlanLimitException("max_storage", "Storage quota exceeded ("
                    + (plan.maxStorageBytes() >> 30) + " GB on the " + plan.tierName() + " plan)");
        }
    }

    public Optional<Subscription> subscription(String userId) {
        return Optional.ofNullable(byUserId.get(userId)).filter(Subscription::active);
    }

    /** Called by the billing integration when a subscription starts/renews. */
    public void grantPremium(String userId, long storageBytes, long expiresAtEpochMs) {
        byUserId.put(userId, new Subscription(userId, storageBytes, expiresAtEpochMs));
        persist();
    }

    /** Called on cancellation/expiry webhook. */
    public void revokePremium(String userId) {
        byUserId.remove(userId);
        persist();
    }

    private void load() {
        if (!Files.exists(persistFile)) {
            return;
        }
        try {
            List<Subscription> subs = json.readValue(Files.readAllBytes(persistFile),
                    new TypeReference<>() {
                    });
            subs.forEach(s -> byUserId.put(s.userId(), s));
        } catch (IOException e) {
            System.err.println("Could not load subscriptions: " + e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            Files.createDirectories(persistFile.getParent());
            Path tmp = persistFile.resolveSibling(persistFile.getFileName() + ".tmp");
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(tmp.toFile(), List.copyOf(byUserId.values()));
            Files.move(tmp, persistFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Could not persist subscriptions: " + e.getMessage());
        }
    }
}
