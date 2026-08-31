package p2p.ws;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The ONE facade for real-time events (backbone §6.4). Services never talk
 * to {@link WebSocketServer} directly — they call this, which builds the
 * canonical payloads and hands them to the transport (user-targeted send or
 * local broadcast).
 *
 * <p>Payload builders are public and pure so tests verify the wire format
 * without a live connection.
 */
public final class WebSocketNotifier {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BiConsumer<String, String> userSender;
    private final Consumer<String> broadcaster;

    public WebSocketNotifier(BiConsumer<String, String> userSender,
                             Consumer<String> broadcaster) {
        this.userSender = userSender;
        this.broadcaster = broadcaster;
    }

    public static WebSocketNotifier over(WebSocketServer server) {
        return new WebSocketNotifier(server::sendToUser, server::broadcast);
    }

    // ── Payload builders (pure, tested) ──────────────────────────────────

    public static Map<String, Object> buildProgressPayload(String sessionId,
            long bytesTransferred, long totalBytes, long speedBps) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "TRANSFER_PROGRESS");
        payload.put("session_id", sessionId);
        payload.put("bytes_transferred", bytesTransferred);
        payload.put("total_bytes", totalBytes);
        payload.put("percentage", totalBytes <= 0 ? 100.0
                : Math.round(10_000.0 * bytesTransferred / totalBytes) / 100.0);
        payload.put("speed_bps", speedBps);
        payload.put("eta_seconds", speedBps <= 0 ? -1
                : (totalBytes - bytesTransferred) / speedBps);
        return payload;
    }

    public static Map<String, Object> buildStatusPayload(String sessionId, String status,
                                                         String checksum) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "TRANSFER_STATUS");
        payload.put("session_id", sessionId);
        payload.put("status", status);
        if (checksum != null) {
            payload.put("checksum", checksum);
        }
        return payload;
    }

    public static Map<String, Object> buildTransferRequestPayload(String requestId,
            String fromUsername, String fileName, long fileSizeBytes, long expiresAtEpochMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "TRANSFER_REQUEST");
        payload.put("request_id", requestId);
        payload.put("from_username", fromUsername.startsWith("@")
                ? fromUsername : "@" + fromUsername);
        payload.put("file_name", fileName);
        payload.put("file_size_bytes", fileSizeBytes);
        payload.put("expires_at", expiresAtEpochMs);
        return payload;
    }

    public static Map<String, Object> buildRequestAcceptedPayload(String requestId) {
        return Map.of("event", "REQUEST_ACCEPTED", "request_id", requestId);
    }

    public static Map<String, Object> buildRequestRejectedPayload(String requestId) {
        return Map.of("event", "REQUEST_REJECTED", "request_id", requestId);
    }

    public static Map<String, Object> buildNotificationPayload(String type, String title,
                                                               String body) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "NOTIFICATION");
        payload.put("type", type);
        payload.put("title", title);
        payload.put("body", body);
        return payload;
    }

    // ── Send methods (backbone §6.4 event catalogue) ─────────────────────

    /** Progress is session-scoped; local clients watching the queue get it. */
    public void sendProgress(String sessionId, long bytesTransferred, long totalBytes,
                             long speedBps) {
        broadcast(buildProgressPayload(sessionId, bytesTransferred, totalBytes, speedBps));
    }

    public void sendStatus(String sessionId, String status, String checksum) {
        broadcast(buildStatusPayload(sessionId, status, checksum));
    }

    public void sendTransferRequest(String receiverUserId, String requestId,
                                    String fromUsername, String fileName,
                                    long fileSizeBytes, long expiresAtEpochMs) {
        send(receiverUserId, buildTransferRequestPayload(
                requestId, fromUsername, fileName, fileSizeBytes, expiresAtEpochMs));
    }

    public void sendRequestAccepted(String senderUserId, String requestId) {
        send(senderUserId, buildRequestAcceptedPayload(requestId));
    }

    public void sendRequestRejected(String senderUserId, String requestId) {
        send(senderUserId, buildRequestRejectedPayload(requestId));
    }

    public void sendNotification(String userId, String type, String title, String body) {
        send(userId, buildNotificationPayload(type, title, body));
    }

    private void send(String userId, Map<String, Object> payload) {
        try {
            userSender.accept(userId, JSON.writeValueAsString(payload));
        } catch (Exception e) {
            System.err.println("WS send failed: " + e.getMessage());
        }
    }

    private void broadcast(Map<String, Object> payload) {
        try {
            broadcaster.accept(JSON.writeValueAsString(payload));
        } catch (Exception e) {
            System.err.println("WS broadcast failed: " + e.getMessage());
        }
    }
}
