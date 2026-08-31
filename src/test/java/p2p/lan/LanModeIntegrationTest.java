package p2p.lan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import p2p.controller.FileController;
import p2p.util.Hashing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end LAN mode between two full nodes in one JVM: upload to node A,
 * offer to node B over the real HTTP control plane, approve on B, and let B
 * pull the bytes from A's FileSender over the binary protocol.
 * (Discovery is seeded directly; mDNS multicast is not available in CI.)
 */
class LanModeIntegrationTest {

    private static final int FILE_SIZE = 8 * 1024 * 1024 + 777;

    @TempDir
    Path dataDirA;
    @TempDir
    Path dataDirB;
    @TempDir
    Path downloadsA;
    @TempDir
    Path downloadsB;

    private FileController nodeA;
    private FileController nodeB;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private byte[] fileContent;

    @BeforeEach
    void setUp() throws IOException {
        nodeA = new FileController(0, dataDirA, downloadsA);
        nodeB = new FileController(0, dataDirB, downloadsB);
        nodeA.start();
        nodeB.start();

        // Seed what mDNS would normally discover: A knows B and vice versa.
        nodeA.deviceRegistry().upsertOnline(nodeB.identity().deviceId(), "NodeB", "TestOS",
                "test", "127.0.0.1", nodeB.port());
        nodeB.deviceRegistry().upsertOnline(nodeA.identity().deviceId(), "NodeA", "TestOS",
                "test", "127.0.0.1", nodeA.port());

        fileContent = new byte[FILE_SIZE];
        new Random(7).nextBytes(fileContent);
    }

    @AfterEach
    void tearDown() {
        nodeA.stop();
        nodeB.stop();
    }

    @Test
    void offerAcceptTransferCompletesAndVerifies() throws Exception {
        int sharePort = upload(nodeA, "lan test.bin", null);

        String offerId = postJson(nodeA, "/lan/send",
                "{\"deviceId\":\"" + nodeB.identity().deviceId() + "\",\"ports\":[" + sharePort + "]}")
                .get("offerId").asText();
        assertTrue(!offerId.isBlank());

        JsonNode offer = await("offer visible on B", () -> firstPendingOffer(nodeB));
        // The offer carries the sender's self-reported identity, not B's alias for it.
        assertEquals(nodeA.identity().name(), offer.get("fromName").asText());
        assertEquals(1, offer.get("fileCount").asInt());
        assertEquals(FILE_SIZE, offer.get("totalBytes").asLong());

        postJson(nodeB, "/lan/offers/" + offer.get("offerId").asText(), "{\"action\":\"accept\"}");

        await("receive completed on B", () -> transferWithStatus(nodeB, "RECEIVE", "COMPLETED"));
        Path received = downloadsB.resolve("lan test.bin");
        assertTrue(Files.exists(received), "file should land in B's downloads");
        assertArrayEquals(Hashing.sha256Unchecked(received),
                java.security.MessageDigest.getInstance("SHA-256").digest(fileContent));

        // Sender saw the completion report.
        await("send marked completed on A", () -> transferWithStatus(nodeA, "SEND", "COMPLETED"));
    }

    @Test
    void folderStructureIsPreserved() throws Exception {
        int sharePort = upload(nodeA, "cat.bin", "photos/2024/cat.bin");

        postJson(nodeA, "/lan/send",
                "{\"deviceId\":\"" + nodeB.identity().deviceId() + "\",\"ports\":[" + sharePort + "]}");
        JsonNode offer = await("offer visible on B", () -> firstPendingOffer(nodeB));
        postJson(nodeB, "/lan/offers/" + offer.get("offerId").asText(), "{\"action\":\"accept\"}");

        await("receive completed on B", () -> transferWithStatus(nodeB, "RECEIVE", "COMPLETED"));
        assertTrue(Files.exists(downloadsB.resolve("photos/2024/cat.bin")),
                "nested folders from the offer should be recreated");
    }

    @Test
    void rejectedOfferReportsBackToSender() throws Exception {
        int sharePort = upload(nodeA, "unwanted.bin", null);

        postJson(nodeA, "/lan/send",
                "{\"deviceId\":\"" + nodeB.identity().deviceId() + "\",\"ports\":[" + sharePort + "]}");
        JsonNode offer = await("offer visible on B", () -> firstPendingOffer(nodeB));
        postJson(nodeB, "/lan/offers/" + offer.get("offerId").asText(), "{\"action\":\"reject\"}");

        await("send marked rejected on A", () -> transferWithStatus(nodeA, "SEND", "REJECTED"));
        assertEquals(0, getJson(nodeB, "/lan/offers").size(), "offer no longer pending");
    }

    @Test
    void manualConnectRegistersBothDevicesWithoutDiscovery() throws Exception {
        // Fresh nodes know nothing about each other (no registry seeding here):
        // /lan/connect must introduce them mutually in one call.
        JsonNode result = postJson(nodeA, "/lan/connect",
                "{\"host\":\"127.0.0.1\",\"port\":" + nodeB.port() + "}");
        assertEquals(nodeB.identity().deviceId(), result.get("deviceId").asText());

        boolean aKnowsB = getJson(nodeA, "/lan/devices").get("devices").findValuesAsText("deviceId")
                .contains(nodeB.identity().deviceId());
        boolean bKnowsA = getJson(nodeB, "/lan/devices").get("devices").findValuesAsText("deviceId")
                .contains(nodeA.identity().deviceId());
        assertTrue(aKnowsB, "A should have B registered");
        assertTrue(bKnowsA, "B should have A registered");
    }

    @Test
    void trustedDeviceIsAutoAccepted() throws Exception {
        nodeB.deviceRegistry().setTrusted(nodeA.identity().deviceId(), true);
        int sharePort = upload(nodeA, "auto.bin", null);

        postJson(nodeA, "/lan/send",
                "{\"deviceId\":\"" + nodeB.identity().deviceId() + "\",\"ports\":[" + sharePort + "]}");

        // No manual accept: completes because A is trusted by B.
        await("receive completed on B", () -> transferWithStatus(nodeB, "RECEIVE", "COMPLETED"));
        assertTrue(Files.exists(downloadsB.resolve("auto.bin")));
    }

    // ---------- helpers ----------

    private int upload(FileController node, String fileName, String relativePath) throws Exception {
        String boundary = "----peerlinktest";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(fileContent);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + node.port() + "/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (relativePath != null) {
            request.header("X-Relative-Path", relativePath);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).get("port").asInt();
    }

    private JsonNode postJson(FileController node, String path, String body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + node.port() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return json.readTree(response.body());
    }

    private JsonNode getJson(FileController node, String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + node.port() + path))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return json.readTree(response.body());
    }

    private JsonNode transferWithStatus(FileController node, String direction, String status) {
        try {
            for (JsonNode t : getJson(node, "/transfers")) {
                if (t.get("direction").asText().equals(direction)
                        && t.get("status").asText().equals(status)) {
                    return t;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode firstPendingOffer(FileController node) {
        try {
            JsonNode array = getJson(node, "/lan/offers");
            return array != null && array.size() > 0 ? array.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private <T> T await(String what, Supplier<T> probe) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            T result = probe.get();
            if (result != null) {
                return result;
            }
            Thread.sleep(150);
        }
        fail("Timed out waiting for: " + what);
        return null; // unreachable
    }
}
