package p2p.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import p2p.controller.FileController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Upload progress SSE + plan speed fields, against a real booted server. */
class ProgressEndpointTest {

    @TempDir
    static Path tmp;

    static FileController controller;
    static String base;
    static String token;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void boot() throws Exception {
        controller = new FileController(0, tmp.resolve("data"), tmp.resolve("dl"));
        controller.start();
        base = "http://localhost:" + controller.port();

        String user = "prog" + UUID.randomUUID().toString().substring(0, 8);
        post("/api/v1/auth/register",
                "{\"username\":\"" + user + "\",\"password\":\"Progress1!\"}");
        String login = post("/api/v1/auth/login",
                "{\"username\":\"" + user + "\",\"password\":\"Progress1!\"}").body();
        token = login.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    @AfterAll
    static void shutdown() {
        controller.stop();
    }

    private static HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void planEndpoint_includesUploadSpeed() throws Exception {
        HttpResponse<String> plan = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/plan"))
                        .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, plan.statusCode());
        assertTrue(plan.body().contains("\"uploadSpeedBytesPerSecond\":2097152"),
                "FREE plan speed missing: " + plan.body());
        assertTrue(plan.body().contains("\"uploadSpeedLabel\":\"2 MB/s\""),
                "FREE label missing: " + plan.body());
    }

    @Test
    void progressEndpoint_returnsUploadState() throws Exception {
        // Real upload with a client-chosen uploadId (the live-progress flow).
        String uploadId = UUID.randomUUID().toString();
        String boundary = "----fylo" + System.nanoTime();
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"p.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "progress endpoint test payload\r\n"
                + "--" + boundary + "--\r\n";
        HttpResponse<String> upload = http.send(
                HttpRequest.newBuilder(URI.create(
                                base + "/api/v1/links/upload?uploadId=" + uploadId))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, upload.statusCode(), upload.body());
        assertTrue(upload.body().contains("\"uploadId\":\"" + uploadId + "\""),
                "uploadId missing from upload response: " + upload.body());

        HttpResponse<String> progress = http.send(
                HttpRequest.newBuilder(URI.create(
                                base + "/api/v1/uploads/" + uploadId + "/progress"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, progress.statusCode());
        assertEquals("text/event-stream",
                progress.headers().firstValue("Content-Type").orElse(""));
        assertTrue(progress.body().contains("bytesTransferred"), progress.body());
        assertTrue(progress.body().contains("COMPLETED"), progress.body());
    }

    @Test
    void progressEndpoint_404_forUnknownUploadId() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(
                                base + "/api/v1/uploads/nonexistent-id/progress"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
    }
}
