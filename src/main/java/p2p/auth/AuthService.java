package p2p.auth;

import p2p.plan.PlanService;
import p2p.user.User;
import p2p.user.UserRepository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The ONE authentication layer. Registration, login, refresh rotation, and
 * bearer-token authentication for every mode that needs an account
 * (Username Share, Link Share). Guest modes (Direct, Nearby) never pass
 * through here — by design they have no account requirement.
 */
public final class AuthService {

    public record TokenPair(String accessToken, String refreshToken, long accessExpiresInSec) {
    }

    /** Thrown for user-facing auth failures; message is safe to return. */
    public static final class AuthException extends Exception {
        public AuthException(String message) {
            super(message);
        }
    }

    static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    static final Duration REFRESH_TTL = Duration.ofDays(30);
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9_]{3,20}$");

    private final UserRepository users;
    private final JwtService jwt;
    private final SessionService sessions;
    private final PlanService plans;

    public AuthService(UserRepository users, JwtService jwt, SessionService sessions,
                       PlanService plans) {
        this.users = users;
        this.jwt = jwt;
        this.sessions = sessions;
        this.plans = plans;
    }

    public User register(String username, String displayName, String email, char[] password)
            throws AuthException {
        String normalized = username == null ? "" : username.strip().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        if (!USERNAME.matcher(normalized).matches()) {
            throw new AuthException("Username must be 3-20 chars: a-z, 0-9, underscore");
        }
        if (password == null || password.length < 8) {
            throw new AuthException("Password must be at least 8 characters");
        }
        if (users.findByUsername(normalized).isPresent()) {
            throw new AuthException("Username is taken");
        }
        User user = new User(UUID.randomUUID().toString(), normalized,
                displayName == null || displayName.isBlank() ? normalized : displayName.strip(),
                email == null || email.isBlank() ? null : email.strip(),
                PasswordHasher.hash(password),
                "FREE", 0, System.currentTimeMillis());
        users.save(user);
        return user;
    }

    public TokenPair login(String username, char[] password) throws AuthException {
        User user = users.findByUsername(username)
                .filter(u -> PasswordHasher.verify(password, u.passwordHash()))
                .orElseThrow(() -> new AuthException("Invalid username or password"));
        return issuePair(user);
    }

    /** Rotates the refresh token: old one is consumed, a new pair is issued. */
    public TokenPair refresh(String refreshToken) throws AuthException {
        JwtService.Claims claims = jwt.verify(refreshToken, JwtService.TokenType.REFRESH)
                .orElseThrow(() -> new AuthException("Invalid refresh token"));
        if (!sessions.consume(claims.tokenId(), claims.subject())) {
            // Possible replay of a rotated token — revoke the whole family.
            sessions.revokeAllFor(claims.subject());
            throw new AuthException("Refresh token is no longer valid");
        }
        User user = users.findById(claims.subject())
                .orElseThrow(() -> new AuthException("Account no longer exists"));
        return issuePair(user);
    }

    public void logout(String refreshToken) {
        jwt.verify(refreshToken, JwtService.TokenType.REFRESH)
                .ifPresent(claims -> sessions.consume(claims.tokenId(), claims.subject()));
    }

    /** Validates an access token from an Authorization: Bearer header. */
    public Optional<AuthContext> authenticate(String bearerToken) {
        return jwt.verify(bearerToken, JwtService.TokenType.ACCESS)
                .map(claims -> new AuthContext(claims.subject(), claims.username()));
    }

    private TokenPair issuePair(User user) {
        // Plan claim (backbone §8.3): PlanService is the live authority; the
        // user row's planType is the persisted mirror.
        String plan = plans.entitlementsFor(user.userId()).tierName();
        String access = jwt.issue(user.userId(), user.username(), plan,
                JwtService.TokenType.ACCESS, ACCESS_TTL);
        String refresh = jwt.issue(user.userId(), user.username(), plan,
                JwtService.TokenType.REFRESH, REFRESH_TTL);
        // Register the refresh jti so it can be consumed exactly once.
        jwt.verify(refresh, JwtService.TokenType.REFRESH).ifPresent(claims ->
                sessions.register(claims.tokenId(), user.userId(), claims.expiresAtEpochSec()));
        return new TokenPair(access, refresh, ACCESS_TTL.toSeconds());
    }
}
