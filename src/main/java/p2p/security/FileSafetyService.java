package p2p.security;

import java.util.Locale;
import java.util.Set;

/**
 * Malicious-file guardrails for PUBLIC distribution surfaces (Link Share).
 * Live modes (Direct/Nearby/Username) are consent-based person-to-person
 * transfers and are not filtered — the receiver explicitly accepts a named
 * file from a known counterparty.
 *
 * <p>Checks: executable/script extension blocklist (including disguised
 * double extensions like {@code report.pdf.exe}), Windows reserved device
 * names, and control characters. Filename sanitization (path traversal) is
 * already enforced upstream by {@code FileReceiver.sanitizeFilename}; this
 * re-checks defensively. Content-based scanning (ClamAV sidecar) hooks in
 * behind this same verdict API — see the Part 2 security doc.
 */
public final class FileSafetyService {

    public record Verdict(boolean allowed, String reason) {

        static Verdict ok() {
            return new Verdict(true, null);
        }

        static Verdict blocked(String reason) {
            return new Verdict(false, reason);
        }
    }

    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "msi", "bat", "cmd", "com", "scr", "pif", "cpl", "msc", "jar",
            "ps1", "psm1", "vbs", "vbe", "js", "jse", "wsf", "wsh", "hta",
            "apk", "app", "deb", "rpm", "dmg", "sh");

    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private final boolean blockExecutables;

    public FileSafetyService() {
        // Default ON for public links; operators can opt out.
        this(!"false".equalsIgnoreCase(System.getenv("FYLO_BLOCK_EXECUTABLES")));
    }

    public FileSafetyService(boolean blockExecutables) {
        this.blockExecutables = blockExecutables;
    }

    public Verdict check(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Verdict.blocked("Missing file name");
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.chars().anyMatch(c -> c < 0x20) || name.contains("..")
                || name.contains("/") || name.contains("\\")) {
            return Verdict.blocked("Illegal characters in file name");
        }
        String base = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (WINDOWS_RESERVED.contains(base)) {
            return Verdict.blocked("Reserved file name");
        }
        if (blockExecutables) {
            // The LAST extension decides execution on every OS; checking it
            // also catches disguised names like invoice.pdf.exe.
            int dot = name.lastIndexOf('.');
            String extension = dot == -1 ? "" : name.substring(dot + 1);
            if (BLOCKED_EXTENSIONS.contains(extension)) {
                return Verdict.blocked("Executable files cannot be shared via public links ("
                        + extension + ")");
            }
        }
        return Verdict.ok();
    }
}
