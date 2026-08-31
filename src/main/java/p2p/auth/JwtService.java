package p2p.auth;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal HS256 JWT issue/verify — the ONE token authority for the platform.
 * Access tokens, refresh tokens, and device tokens are all JWTs signed with
 * the same server secret and distinguished by a {@code typ} claim, so every
 * API handler validates credentials through this single class.
 *
 * <p>The signing secret is generated once and persisted under the data
 * directory, so tokens survive restarts. (In a multi-node deployment the
 * secret moves to shared config — see the architecture doc.)
 */
public final class JwtService {

    public enum TokenType { ACCESS, REFRESH, DEVICE }

    /** Verified token contents. */
    public record Claims(String subject, String username, String plan, TokenType type,
                         String tokenId, long expiresAtEpochSec) {
    }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String HEADER_B64 = B64.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final ObjectMapper json = new ObjectMapper();
    private final byte[] secret;

    public JwtService(Path dataDir) throws IOException {
        this.secret = loadOrCreateSecret(dataDir.resolve("jwt.secret"));
    }

    public String issue(String userId, String username, String plan, TokenType type,
                        Duration ttl) {
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> payload = new HashMap<>();
        payload.put("sub", userId);
        payload.put("preferred_username", username);
        payload.put("plan", plan == null ? "FREE" : plan); // backbone §8.3 payload
        payload.put("typ", type.name());
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("iat", now);
        payload.put("exp", now + ttl.toSeconds());
        try {
            String body = B64.encodeToString(json.writeValueAsBytes(payload));
            String signingInput = HEADER_B64 + "." + body;
            return signingInput + "." + B64.encodeToString(hmac(signingInput));
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialize JWT payload", e);
        }
    }

    /** Verifies signature, expiry, and expected type; empty on any failure. */
    public Optional<Claims> verify(String token, TokenType expectedType) {
        if (token == null) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !parts[0].equals(HEADER_B64)) {
            return Optional.empty();
        }
        byte[] expectedSig = hmac(parts[0] + "." + parts[1]);
        byte[] actualSig;
        try {
            actualSig = B64D.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expectedSig, actualSig)) {
            return Optional.empty();
        }
        try {
            Map<?, ?> payload = json.readValue(B64D.decode(parts[1]), Map.class);
            long exp = ((Number) payload.get("exp")).longValue();
            if (exp < System.currentTimeMillis() / 1000) {
                return Optional.empty();
            }
            TokenType type = TokenType.valueOf((String) payload.get("typ"));
            if (type != expectedType) {
                return Optional.empty();
            }
            return Optional.of(new Claims((String) payload.get("sub"),
                    (String) payload.get("preferred_username"),
                    (String) payload.get("plan"), type,
                    (String) payload.get("jti"), exp));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new AssertionError("HmacSHA256 is mandatory in every JRE", e);
        }
    }

    private static byte[] loadOrCreateSecret(Path file) throws IOException {
        if (Files.exists(file)) {
            return Files.readAllBytes(file);
        }
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        Files.createDirectories(file.getParent());
        Files.write(file, secret);
        return secret;
    }
}
