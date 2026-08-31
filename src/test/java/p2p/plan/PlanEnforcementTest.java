package p2p.plan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import p2p.auth.AuthContext;
import p2p.engine.TransferEngine;
import p2p.security.FileSafetyService;
import p2p.service.FileSharer;
import p2p.share.LinkShareService;
import p2p.share.ShareLink;
import p2p.storage.LocalStorageProvider;
import p2p.transfer.TransferManager;
import p2p.user.JsonUserRepository;
import p2p.user.TransferHistoryService;
import p2p.user.User;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Free vs Premium enforcement at the service layer (Link Share is the
 * gatekeeper for storage-backed entitlements).
 */
class PlanEnforcementTest {

    @TempDir
    Path dataDir;

    private TransferEngine engine;
    private PlanService plans;
    private LinkShareService links;
    private JsonUserRepository users;
    // History persists by UUID (transfer_history.user_id), so use a real one.
    private final AuthContext user = new AuthContext(
            java.util.UUID.randomUUID().toString(), "nisidh");

    @BeforeEach
    void setUp() throws IOException {
        engine = new TransferEngine(new FileSharer(), new TransferManager(1),
                new LocalStorageProvider(dataDir));
        plans = new PlanService(dataDir);
        users = new JsonUserRepository(dataDir);
        users.save(new User(user.userId(), user.username(), user.username(), null,
                "x", "FREE", 0, System.currentTimeMillis()));
        links = new LinkShareService(engine,
                new TransferHistoryService(
                        new p2p.transfer.JsonTransferHistoryRepository(dataDir)),
                plans, new FileSafetyService(true), users, dataDir);
    }

    @AfterEach
    void tearDown() {
        links.close();
        engine.close();
    }

    private ShareLink create(LinkShareService.LinkOptions options) throws IOException {
        byte[] bytes = "hello fylo".getBytes(StandardCharsets.UTF_8);
        return links.create(user, new ByteArrayInputStream(bytes), "notes.txt",
                bytes.length, options);
    }

    @Test
    void freeDefaultsToTwoDayExpiry() throws IOException {
        ShareLink link = create(LinkShareService.LinkOptions.DEFAULTS);
        long ttl = link.expiresAtEpochMs() - link.createdAtEpochMs();
        long twoDays = Duration.ofDays(2).toMillis();
        assertTrue(Math.abs(ttl - twoDays) < 60_000, "free links expire in ~2 days");
    }

    @Test
    void freeCannotUsePremiumFeatures() {
        assertEquals("password_links", assertThrows(PlanLimitException.class, () ->
                create(new LinkShareService.LinkOptions(null, "secret", 0))).limit());
        assertEquals("download_limits", assertThrows(PlanLimitException.class, () ->
                create(new LinkShareService.LinkOptions(null, null, 5))).limit());
        assertEquals("link_expiry", assertThrows(PlanLimitException.class, () ->
                create(new LinkShareService.LinkOptions(Duration.ofDays(30), null, 0))).limit());
        assertEquals("link_revocation", assertThrows(PlanLimitException.class, () ->
                links.revoke(user, "whatever")).limit());
    }

    @Test
    void premiumUnlocksPasswordDownloadLimitsAndRevocation() throws IOException {
        plans.grantPremium(user.userId(), 500L << 30, 0);

        ShareLink link = create(new LinkShareService.LinkOptions(
                Duration.ofDays(30), "secret", 2));
        assertTrue(link.passwordProtected());
        assertEquals(2, link.maxDownloads());
        assertTrue(links.passwordMatches(link, "secret"));
        assertFalse(links.passwordMatches(link, "wrong"));

        // Download limit: after 2 downloads the link stops resolving.
        links.recordDownload(link.slug());
        links.recordDownload(link.slug());
        assertTrue(links.resolve(link.slug()).isEmpty(), "exhausted link must not resolve");

        // Revocation kills the link but keeps the record.
        ShareLink second = create(new LinkShareService.LinkOptions(null, null, 0));
        assertTrue(links.revoke(user, second.slug()));
        assertTrue(links.resolve(second.slug()).isEmpty(), "revoked link must not resolve");
        assertTrue(links.listFor(user).stream()
                .anyMatch(l -> l.slug().equals(second.slug()) && l.revoked()));
    }

    @Test
    void premiumNeverExpire() throws IOException {
        plans.grantPremium(user.userId(), 500L << 30, 0);
        ShareLink link = create(new LinkShareService.LinkOptions(Duration.ZERO, null, 0));
        assertEquals(Long.MAX_VALUE, link.expiresAtEpochMs());
    }

    @Test
    void executablesBlockedOnPublicLinks() {
        byte[] bytes = new byte[]{0x4D, 0x5A};
        PlanLimitException blocked = assertThrows(PlanLimitException.class, () ->
                links.create(user, new ByteArrayInputStream(bytes), "invoice.pdf.exe",
                        bytes.length, LinkShareService.LinkOptions.DEFAULTS));
        assertEquals("file_blocked", blocked.limit());
    }

    @Test
    void expiredSubscriptionFallsBackToFree() {
        plans.grantPremium(user.userId(), 500L << 30, System.currentTimeMillis() - 1000);
        assertEquals("FREE", plans.entitlementsFor(user.userId()).tierName());
    }

    @Test
    void freeUserCannotExceed10ActiveLinks() throws IOException {
        for (int i = 0; i < 10; i++) {
            create(LinkShareService.LinkOptions.DEFAULTS);
        }
        PlanLimitException capped = assertThrows(PlanLimitException.class,
                () -> create(LinkShareService.LinkOptions.DEFAULTS));
        assertEquals("active_links", capped.limit());
    }

    @Test
    void premiumUserHasUnlimitedActiveLinks() throws IOException {
        plans.grantPremium(user.userId(), 500L << 30, 0);
        assertEquals(Integer.MAX_VALUE,
                plans.entitlementsFor(user.userId()).maxActiveLinks());
        // Sail past the FREE cap without tripping any limit.
        for (int i = 0; i < 12; i++) {
            create(LinkShareService.LinkOptions.DEFAULTS);
        }
        assertEquals(12, links.activeLinkCount(user.userId()));
    }

    @Test
    void storageAccountingTracksCreateAndDelete() throws IOException {
        var link = create(LinkShareService.LinkOptions.DEFAULTS);
        assertEquals(link.sizeBytes(),
                users.findById(user.userId()).orElseThrow().storageUsedBytes());
        links.delete(user, link.slug());
        assertEquals(0, users.findById(user.userId()).orElseThrow().storageUsedBytes());
    }
}
