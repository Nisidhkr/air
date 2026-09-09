package p2p.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import p2p.controller.FileController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentApiTest {

    @TempDir
    Path dataDir;
    @TempDir
    Path downloadsDir;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private FileController controller;

    @AfterEach
    void tearDown() {
        if (controller != null) {
            controller.stop();
        }
    }

    @Test
    void exposesLoopbackAgentStatusAndDeviceViews() throws Exception {
        controller = new FileController(0, dataDir, downloadsDir);
        controller.start();

        JsonNode status = get("/agent/status");
        assertEquals(200, status.get("httpStatus").asInt());
        assertEquals("READY", status.get("body").get("state").asText());
        assertEquals("_fylo._tcp.local.", status.get("body").get("mdnsServiceType").asText());
        assertTrue(status.get("body").get("loopbackOnly").asBoolean());

        JsonNode device = get("/agent/device");
        assertEquals(controller.identity().deviceId(), device.get("body").get("deviceId").asText());
        assertEquals(controller.port(), device.get("body").get("apiPort").asInt());
    }

    @Test
    void preservesUnifiedAuthApiAlongsideAgentApi() throws Exception {
        controller = new FileController(0, dataDir, downloadsDir);
        controller.start();

        JsonNode registration = post("/api/v1/auth/register",
                "{\"username\":\"agent_user\",\"password\":\"secret123\","
                        + "\"email\":\"agent@example.com\"}");
        assertEquals(201, registration.get("httpStatus").asInt());
        assertTrue(registration.get("body").has("tokens"));

        JsonNode login = post("/api/v1/auth/login",
                "{\"username\":\"agent_user\",\"password\":\"secret123\"}");
        assertEquals(200, login.get("httpStatus").asInt());
        assertTrue(login.get("body").get("accessToken").asText().length() > 20);
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + controller.port() + path))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return json.readTree("{\"httpStatus\":" + response.statusCode()
                + ",\"body\":" + response.body() + "}");
    }

    private JsonNode post(String path, String body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + controller.port() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return json.readTree("{\"httpStatus\":" + response.statusCode()
                + ",\"body\":" + response.body() + "}");
    }
}
