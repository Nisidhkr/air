package p2p.plan;

import java.time.Duration;
import java.util.Set;

/**
 * What a plan allows. Pure data — every enforcement point asks
 * {@link PlanService} for the caller's entitlements and checks against
 * these numbers; no mode service hard-codes a limit.
 */
public record Entitlements(
        String tierName,
        long maxFileBytes,
        long maxStorageBytes,
        int maxActiveLinks,
        Set<Integer> linkExpiryDayOptions,   // selectable TTLs
        boolean customExpiryAllowed,         // arbitrary TTL within maxExpiry
        boolean neverExpireAllowed,
        Duration maxLinkTtl,                 // cap for custom expiry
        boolean passwordProtectedLinks,
        boolean downloadLimits,
        boolean linkAnalytics,
        boolean linkRevocation,
        int maxConcurrentTransfers,
        int historyEntries,                  // Integer.MAX_VALUE = unlimited
        int maxTrustedDevices,               // Integer.MAX_VALUE = unlimited
        boolean ecosystemSync,               // clipboard/photo/folder sync
        long uploadSpeedBytesPerSecond) {    // Long.MAX_VALUE = unlimited

    private static final long GB = 1L << 30;

    /** True when upload speed is capped (FREE tier). */
    public boolean isThrottled() {
        return uploadSpeedBytesPerSecond != Long.MAX_VALUE;
    }

    /** Human label for the speed cap: "2 MB/s", "500 KB/s", "Unlimited". */
    public String uploadSpeedLabel() {
        if (uploadSpeedBytesPerSecond == Long.MAX_VALUE) {
            return "Unlimited";
        }
        if (uploadSpeedBytesPerSecond >= 1_000_000) {
            return (uploadSpeedBytesPerSecond / 1_000_000) + " MB/s";
        }
        return (uploadSpeedBytesPerSecond / 1_000) + " KB/s";
    }

    /**
     * FREE: Direct + Nearby unlimited (they never touch storage, so no
     * storage entitlements apply); Username basic; Link Share 2-day expiry,
     * 10 GB file, 10 GB storage, 10 active links.
     */
    public static final Entitlements FREE = new Entitlements(
            "FREE",
            10 * GB,            // max file
            10 * GB,            // max storage
            10,                 // max active links
            Set.of(2),          // 2-day expiry only
            false,              // no custom expiry
            false,              // no never-expire
            Duration.ofDays(2),
            false,              // no password links
            false,              // no download limits
            false,              // no analytics
            false,              // no revocation (delete only)
            3,                  // concurrent transfers
            100,                // basic history
            5,                  // trusted devices
            false,              // no ecosystem sync
            2L * 1024 * 1024);  // 2 MB/s upload throttle

    /** PREMIUM: storage varies by subscription tier (500 GB / 1 TB / 2 TB). */
    public static Entitlements premium(long storageBytes) {
        return new Entitlements(
                "PREMIUM",
                200 * GB,                    // 100 GB+ per file
                storageBytes,
                Integer.MAX_VALUE,           // unlimited active links (backbone §13.2)
                Set.of(7, 30, 90),
                true,                        // custom expiry
                true,                        // never expire
                Duration.ofDays(3650),
                true,                        // password links
                true,                        // download limits
                true,                        // analytics
                true,                        // revocation
                10,                          // priority / more concurrency
                Integer.MAX_VALUE,           // unlimited history
                Integer.MAX_VALUE,           // unlimited devices
                true,                        // ecosystem sync
                Long.MAX_VALUE);             // unlimited upload speed
    }
}
