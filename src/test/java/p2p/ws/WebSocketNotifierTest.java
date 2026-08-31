package p2p.ws;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backbone §6.4 payload contracts — no live connection required. */
class WebSocketNotifierTest {

    @Test
    void buildProgressPayload_hasAllFields() {
        Map<String, Object> p = WebSocketNotifier.buildProgressPayload(
                "sess-1", 10_485_760L, 42_949_672_960L, 52_428_800L);
        assertEquals("TRANSFER_PROGRESS", p.get("event"));
        assertEquals("sess-1", p.get("session_id"));
        assertEquals(10_485_760L, p.get("bytes_transferred"));
        assertEquals(42_949_672_960L, p.get("total_bytes"));
        assertEquals(0.02, (double) p.get("percentage"), 0.001); // 10 MB of 40 GB
        assertEquals(52_428_800L, p.get("speed_bps"));
        assertTrue((long) p.get("eta_seconds") > 0);
    }

    @Test
    void buildStatusPayload_completedHasChecksum() {
        Map<String, Object> p = WebSocketNotifier.buildStatusPayload(
                "sess-2", "COMPLETED", "abc123");
        assertEquals("TRANSFER_STATUS", p.get("event"));
        assertEquals("COMPLETED", p.get("status"));
        assertEquals("abc123", p.get("checksum"));
        // FAILED without checksum omits the field rather than sending null.
        assertTrue(!WebSocketNotifier.buildStatusPayload("s", "FAILED", null)
                .containsKey("checksum"));
    }

    @Test
    void buildTransferRequestPayload_hasExpiry() {
        long expires = System.currentTimeMillis() + 600_000;
        Map<String, Object> p = WebSocketNotifier.buildTransferRequestPayload(
                "req-1", "aman", "video.mp4", 104_857_600L, expires);
        assertEquals("TRANSFER_REQUEST", p.get("event"));
        assertEquals("@aman", p.get("from_username"));
        assertEquals(expires, p.get("expires_at"));
        assertEquals(104_857_600L, p.get("file_size_bytes"));
    }

    @Test
    void buildRequestAcceptedPayload_hasRequestId() {
        assertEquals("req-9",
                WebSocketNotifier.buildRequestAcceptedPayload("req-9").get("request_id"));
        assertEquals("REQUEST_REJECTED",
                WebSocketNotifier.buildRequestRejectedPayload("req-9").get("event"));
    }

    @Test
    void sendRoutesToUserTransport() {
        List<String> sentTo = new ArrayList<>();
        List<String> payloads = new ArrayList<>();
        WebSocketNotifier notifier = new WebSocketNotifier(
                (userId, json) -> {
                    sentTo.add(userId);
                    payloads.add(json);
                },
                payloads::add);
        notifier.sendRequestAccepted("user-7", "req-1");
        assertEquals(List.of("user-7"), sentTo);
        assertTrue(payloads.get(0).contains("\"REQUEST_ACCEPTED\""));

        notifier.sendProgress("sess", 5, 10, 1); // broadcast path
        assertEquals(2, payloads.size());
        assertTrue(payloads.get(1).contains("\"TRANSFER_PROGRESS\""));
    }
}
