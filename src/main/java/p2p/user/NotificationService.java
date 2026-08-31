package p2p.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-user notification queue (transfer requests, accept/reject verdicts,
 * link expiry warnings). Part 1 delivers via drain-on-poll from the API
 * layer; the same queue feeds a WebSocket push channel in Part 2 — producers
 * never change.
 */
public final class NotificationService {

    public record Notification(String id, String type, Map<String, Object> data,
                               long createdAtEpochMs) {
    }

    private static final int MAX_QUEUED_PER_USER = 100;

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Notification>> queues =
            new ConcurrentHashMap<>();
    // Real-time channel (backbone §6.4): pushes also go out over WebSocket.
    private volatile p2p.ws.WebSocketNotifier notifier;

    public void setNotifier(p2p.ws.WebSocketNotifier notifier) {
        this.notifier = notifier;
    }

    public Notification push(String userId, String type, Map<String, Object> data) {
        Notification notification = new Notification(
                UUID.randomUUID().toString(), type, data, System.currentTimeMillis());
        ConcurrentLinkedDeque<Notification> queue =
                queues.computeIfAbsent(userId, id -> new ConcurrentLinkedDeque<>());
        queue.addLast(notification);
        while (queue.size() > MAX_QUEUED_PER_USER) {
            queue.pollFirst();
        }
        if (notifier != null) {
            notifier.sendNotification(userId, type, type.replace('_', ' '),
                    String.valueOf(data));
        }
        return notification;
    }

    /** Returns and removes everything queued for {@code userId}. */
    public List<Notification> drain(String userId) {
        ConcurrentLinkedDeque<Notification> queue = queues.get(userId);
        if (queue == null || queue.isEmpty()) {
            return List.of();
        }
        List<Notification> drained = new ArrayList<>();
        Notification next;
        while ((next = queue.pollFirst()) != null) {
            drained.add(next);
        }
        return drained;
    }
}
