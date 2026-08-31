package p2p.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    @TempDir
    Path dataDir;

    @Test
    void issueAndVerifyRoundTrip() throws IOException {
        JwtService jwt = new JwtService(dataDir);
        String token = jwt.issue("user-1", "nisidh", "FREE", JwtService.TokenType.ACCESS,
                Duration.ofMinutes(5));
        Optional<JwtService.Claims> claims = jwt.verify(token, JwtService.TokenType.ACCESS);
        assertTrue(claims.isPresent());
        assertEquals("user-1", claims.get().subject());
        assertEquals("nisidh", claims.get().username());
        assertEquals("FREE", claims.get().plan(), "backbone §8.3 plan claim");
    }

    @Test
    void planClaimCarriesPremium() throws IOException {
        JwtService jwt = new JwtService(dataDir);
        String token = jwt.issue("user-2", "aman", "PREMIUM", JwtService.TokenType.ACCESS,
                Duration.ofMinutes(5));
        assertEquals("PREMIUM",
                jwt.verify(token, JwtService.TokenType.ACCESS).orElseThrow().plan());
    }

    @Test
    void rejectsWrongType_expired_and_tampered() throws IOException {
        JwtService jwt = new JwtService(dataDir);
        String access = jwt.issue("u", "n", "FREE", JwtService.TokenType.ACCESS,
                Duration.ofMinutes(5));
        assertTrue(jwt.verify(access, JwtService.TokenType.REFRESH).isEmpty(),
                "access token must not verify as refresh");

        String expired = jwt.issue("u", "n", "FREE", JwtService.TokenType.ACCESS,
                Duration.ofSeconds(-10));
        assertTrue(jwt.verify(expired, JwtService.TokenType.ACCESS).isEmpty(),
                "expired token must be rejected");

        String tampered = access.substring(0, access.length() - 4) + "AAAA";
        assertTrue(jwt.verify(tampered, JwtService.TokenType.ACCESS).isEmpty(),
                "bad signature must be rejected");
    }

    @Test
    void secretPersistsAcrossRestarts() throws IOException {
        String token = new JwtService(dataDir)
                .issue("u", "n", "FREE", JwtService.TokenType.ACCESS, Duration.ofMinutes(5));
        JwtService restarted = new JwtService(dataDir);
        assertTrue(restarted.verify(token, JwtService.TokenType.ACCESS).isPresent(),
                "token must survive a service restart (persisted secret)");
    }
}
