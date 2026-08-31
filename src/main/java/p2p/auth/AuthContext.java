package p2p.auth;

/** The authenticated principal attached to a request by the API layer. */
public record AuthContext(String userId, String username) {
}
