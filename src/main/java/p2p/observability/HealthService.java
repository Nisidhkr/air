package p2p.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Actuator-compatible health endpoint (backbone §14.4) for a plain
 * HttpServer app: aggregates named component checks (database, redis,
 * storage) into the familiar {@code {"status":"UP","components":{…}}}
 * shape. 200 when everything is UP, 503 when ANY component is DOWN —
 * exactly what the K8s readiness/liveness probes consume.
 *
 * <p>Checks are injected as lambdas, so tests exercise the aggregation
 * without infrastructure and the composition root decides what "database
 * up" means per deployment (JSON mode is always UP).
 */
public final class HealthService {

    /** One component probe; must not throw. */
    @FunctionalInterface
    public interface Check {
        boolean up();
    }

    /** Aggregated outcome: HTTP status + JSON-shaped body. */
    public record Result(int httpStatus, Map<String, Object> body) {
    }

    private final Map<String, Check> checks = new LinkedHashMap<>();

    public HealthService check(String component, Check check) {
        checks.put(component, check);
        return this;
    }

    public Result report() {
        Map<String, Object> components = new LinkedHashMap<>();
        boolean allUp = true;
        for (Map.Entry<String, Check> entry : checks.entrySet()) {
            boolean up;
            try {
                up = entry.getValue().up();
            } catch (RuntimeException e) {
                up = false;
            }
            allUp &= up;
            components.put(entry.getKey(), Map.of("status", up ? "UP" : "DOWN"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp ? "UP" : "DOWN");
        body.put("components", components);
        return new Result(allUp ? 200 : 503, body);
    }

    /** Handler for /health and /actuator/health. */
    public HttpHandler handler() {
        com.fasterxml.jackson.databind.ObjectMapper json =
                new com.fasterxml.jackson.databind.ObjectMapper();
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                Result result = report();
                byte[] body = json.writeValueAsBytes(result.body());
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(result.httpStatus(), body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        };
    }
}
