package p2p.engine;

import java.util.Optional;

/**
 * The one place that understands share codes. A code is what Direct Share
 * users exchange ("invite code") and what QR payloads embed; today it is
 * {@code <port>-<token>} pointing at a live {@code FileSender}.
 *
 * <p>Keeping encode/decode here means the format can evolve (e.g. to
 * server-brokered rendezvous ids for cross-network transfers) without any
 * mode service changing.
 */
public final class ShareCodes {

    /** A parsed share code. */
    public record Code(int port, String token) {

        public String encoded() {
            return port + "-" + token;
        }

        /** Payload for QR rendering on any client. */
        public String qrPayload() {
            return "air://direct/" + encoded();
        }
    }

    /** 6-char session codes (backbone §11 Mode 1): unambiguous A-Z/2-9 set. */
    private static final char[] SESSION_CODE_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int SESSION_CODE_LENGTH = 6;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private ShareCodes() {
    }

    public static Code of(int port, String token) {
        return new Code(port, token);
    }

    /** Short human-relayable code for a direct-share session ("ABC123" style). */
    public static String generateSessionCode() {
        StringBuilder code = new StringBuilder(SESSION_CODE_LENGTH);
        for (int i = 0; i < SESSION_CODE_LENGTH; i++) {
            code.append(SESSION_CODE_ALPHABET[RANDOM.nextInt(SESSION_CODE_ALPHABET.length)]);
        }
        return code.toString();
    }

    public static Optional<Code> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.strip();
        if (value.startsWith("air://direct/")) {
            value = value.substring("air://direct/".length());
        }
        int dash = value.indexOf('-');
        if (dash <= 0 || dash == value.length() - 1) {
            return Optional.empty();
        }
        try {
            int port = Integer.parseInt(value.substring(0, dash));
            String token = value.substring(dash + 1);
            if (port < 1 || port > 65535 || token.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Code(port, token));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
