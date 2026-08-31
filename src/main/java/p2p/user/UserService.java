package p2p.user;

import java.util.List;
import java.util.Optional;

/**
 * Domain queries over accounts: profiles and @username search, always
 * composed with live presence so clients can render "online" dots without a
 * second call. Mutations (register) live in {@code AuthService}, which owns
 * credential rules.
 */
public final class UserService {

    private static final int SEARCH_LIMIT = 20;

    private final UserRepository users;
    private final PresenceService presence;

    public UserService(UserRepository users, PresenceService presence) {
        this.users = users;
        this.presence = presence;
    }

    public Optional<User.Profile> profileById(String userId) {
        return users.findById(userId).map(u -> u.profile(presence.isOnline(u.userId())));
    }

    public Optional<User.Profile> profileByUsername(String username) {
        return users.findByUsername(username)
                .map(u -> u.profile(presence.isOnline(u.userId())));
    }

    /** @aman-style prefix search. */
    public List<User.Profile> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return users.searchByUsernamePrefix(query, SEARCH_LIMIT).stream()
                .map(u -> u.profile(presence.isOnline(u.userId())))
                .toList();
    }
}
