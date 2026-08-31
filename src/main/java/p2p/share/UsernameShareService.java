package p2p.share;

import p2p.auth.AuthContext;
import p2p.engine.TransferEngine;
import p2p.service.FileSharer;
import p2p.transfer.TransferRequestRecord;
import p2p.transfer.TransferRequestRepository;
import p2p.transfer.TransferSessionRecord;
import p2p.transfer.TransferSessionRepository;
import p2p.user.NotificationService;
import p2p.user.PresenceService;
import p2p.user.TransferHistoryService;
import p2p.user.User;
import p2p.user.UserRepository;
import p2p.ws.WebSocketNotifier;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MODE 3 — Username Share (Telegram-style, login required). The sender's
 * file is offered through the one engine exactly like Direct Share; what this
 * service adds is the request/accept handshake between accounts, presence
 * checks, notifications, and history. The share port/token stay secret until
 * the receiver accepts.
 */
public final class UsernameShareService {

    private static final long REQUEST_TTL_MS = 10 * 60_000; // sender must stay online anyway

    private final TransferEngine engine;
    private final UserRepository users;
    private final PresenceService presence;
    private final NotificationService notifications;
    private final TransferHistoryService history;
    private final ConcurrentHashMap<String, TransferRequest> requests = new ConcurrentHashMap<>();

    // Persistence + real-time (backbone §5.2, §6.4); set by the composition
    // root, null-guarded so unit tests can run without them.
    private volatile TransferSessionRepository sessionRepo;
    private volatile TransferRequestRepository requestRepo;
    private volatile WebSocketNotifier notifier;

    public void setPersistence(TransferSessionRepository sessions,
                               TransferRequestRepository requests) {
        this.sessionRepo = sessions;
        this.requestRepo = requests;
    }

    public void setNotifier(WebSocketNotifier notifier) {
        this.notifier = notifier;
    }

    public UsernameShareService(TransferEngine engine, UserRepository users,
                                PresenceService presence, NotificationService notifications,
                                TransferHistoryService history) {
        this.engine = engine;
        this.users = users;
        this.presence = presence;
        this.notifications = notifications;
        this.history = history;
    }

    /**
     * Creates a transfer request toward {@code toUsername} for the sender's
     * live share on {@code sharePort} (created via the normal upload flow).
     */
    public TransferRequest request(AuthContext sender, String toUsername, int sharePort)
            throws IOException {
        User receiver = users.findByUsername(toUsername)
                .orElseThrow(() -> new IOException("No such user: " + toUsername));
        if (receiver.userId().equals(sender.userId())) {
            throw new IOException("Cannot send a file to yourself");
        }
        FileSharer.ShareInfo info = engine.shareInfo(sharePort)
                .orElseThrow(() -> new IOException("No active share on port " + sharePort));

        TransferRequest request = new TransferRequest(UUID.randomUUID().toString(),
                sender.userId(), sender.username(), receiver.userId(),
                info.fileName(), info.size(), info.port(), info.token(),
                TransferRequest.State.PENDING, System.currentTimeMillis());
        requests.put(request.requestId(), request);
        persistNewRequest(request);

        notifications.push(receiver.userId(), "transfer_request", Map.of(
                "requestId", request.requestId(),
                "fromUsername", sender.username(),
                "fileName", info.fileName(),
                "size", info.size(),
                "fromOnline", presence.isOnline(sender.userId())));
        if (notifier != null) {
            notifier.sendTransferRequest(receiver.userId(), request.requestId(),
                    sender.username(), info.fileName(), info.size(),
                    request.createdAtEpochMs() + REQUEST_TTL_MS);
        }
        return request;
    }

    /** Backbone §5.2: mirror the handshake into transfer_sessions + transfer_requests. */
    private void persistNewRequest(TransferRequest request) {
        if (sessionRepo == null || requestRepo == null) {
            return;
        }
        Instant now = Instant.now();
        TransferSessionRecord session = new TransferSessionRecord(
                UUID.randomUUID(), "USERNAME",
                UUID.fromString(request.fromUserId()), UUID.fromString(request.toUserId()),
                null, null, "PENDING", request.fileName(), 0, request.sizeBytes(), null,
                request.shareToken(), null, null, null, null, now, now);
        sessionRepo.save(session);
        requestRepo.save(new TransferRequestRecord(
                UUID.fromString(request.requestId()), session.id(),
                UUID.fromString(request.fromUserId()), UUID.fromString(request.toUserId()),
                "PENDING", null, request.fileName(), request.sizeBytes(),
                request.sharePort(), request.shareToken(),
                now.plusMillis(REQUEST_TTL_MS), null, now));
    }

    /** Persists a verdict and flips the linked session (accept → CONNECTING). */
    private void persistVerdict(String requestId, String status, String sessionStatus) {
        if (requestRepo == null) {
            return;
        }
        UUID id = UUID.fromString(requestId);
        requestRepo.updateStatus(id, status, Instant.now());
        if (sessionRepo != null && sessionStatus != null) {
            requestRepo.findById(id)
                    .map(TransferRequestRecord::transferSessionId)
                    .ifPresent(sessionId -> sessionRepo.updateStatus(sessionId, sessionStatus));
        }
    }

    /** Pending requests addressed to {@code user} (connection details hidden). */
    public List<Map<String, Object>> incoming(AuthContext user) {
        expireStale();
        return requests.values().stream()
                .filter(r -> r.toUserId().equals(user.userId())
                        && r.state() == TransferRequest.State.PENDING)
                .sorted(Comparator.comparingLong(TransferRequest::createdAtEpochMs).reversed())
                .map(r -> Map.<String, Object>of(
                        "requestId", r.requestId(),
                        "fromUsername", r.fromUsername(),
                        "fileName", r.fileName(),
                        "size", r.sizeBytes(),
                        "createdAt", r.createdAtEpochMs()))
                .toList();
    }

    /**
     * Receiver accepts: the request flips state, the sender is notified, and
     * the connection details (port + token for the engine's download path)
     * are returned to the receiver — and only to the receiver.
     */
    public Map<String, Object> accept(AuthContext user, String requestId) throws IOException {
        TransferRequest request = transition(user, requestId, TransferRequest.State.ACCEPTED);
        persistVerdict(requestId, "ACCEPTED", "CONNECTING");
        notifications.push(request.fromUserId(), "request_accepted", Map.of(
                "requestId", request.requestId(), "fileName", request.fileName()));
        if (notifier != null) {
            notifier.sendRequestAccepted(request.fromUserId(), request.requestId());
        }
        history.record(request.fromUserId(), "SEND", TransferHistoryService.Mode.USERNAME,
                request.fileName(), request.sizeBytes(), "@" + user.username(), "ACCEPTED");
        history.record(user.userId(), "RECEIVE", TransferHistoryService.Mode.USERNAME,
                request.fileName(), request.sizeBytes(), "@" + request.fromUsername(), "ACCEPTED");
        return Map.of(
                "requestId", request.requestId(),
                "fileName", request.fileName(),
                "size", request.sizeBytes(),
                "port", request.sharePort(),
                "token", request.shareToken());
    }

    public void reject(AuthContext user, String requestId) throws IOException {
        TransferRequest request = transition(user, requestId, TransferRequest.State.REJECTED);
        persistVerdict(requestId, "REJECTED", null);
        notifications.push(request.fromUserId(), "request_rejected", Map.of(
                "requestId", request.requestId(), "fileName", request.fileName()));
        if (notifier != null) {
            notifier.sendRequestRejected(request.fromUserId(), request.requestId());
        }
        history.record(request.fromUserId(), "SEND", TransferHistoryService.Mode.USERNAME,
                request.fileName(), request.sizeBytes(), "@" + user.username(), "REJECTED");
    }

    public Optional<TransferRequest> find(String requestId) {
        return Optional.ofNullable(requests.get(requestId));
    }

    private TransferRequest transition(AuthContext user, String requestId,
                                       TransferRequest.State target) throws IOException {
        TransferRequest request = requests.get(requestId);
        if (request == null || !request.toUserId().equals(user.userId())) {
            throw new IOException("Unknown request");
        }
        if (request.state() != TransferRequest.State.PENDING) {
            throw new IOException("Request already " + request.state().name().toLowerCase());
        }
        TransferRequest updated = request.withState(target);
        requests.put(requestId, updated);
        return updated;
    }

    private void expireStale() {
        long cutoff = System.currentTimeMillis() - REQUEST_TTL_MS;
        requests.replaceAll((id, r) ->
                r.state() == TransferRequest.State.PENDING && r.createdAtEpochMs() < cutoff
                        ? r.withState(TransferRequest.State.EXPIRED) : r);
    }
}
