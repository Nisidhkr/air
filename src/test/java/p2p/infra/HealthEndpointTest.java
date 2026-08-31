package p2p.infra;

import org.junit.jupiter.api.Test;
import p2p.observability.HealthService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Backbone §14.4 health aggregation (component checks injected as lambdas). */
class HealthEndpointTest {

    @SuppressWarnings("unchecked")
    private static String componentStatus(HealthService.Result result, String name) {
        Map<String, Object> components = (Map<String, Object>) result.body().get("components");
        return (String) ((Map<String, Object>) components.get(name)).get("status");
    }

    @Test
    void healthEndpoint_returns200_allUp() {
        HealthService health = new HealthService()
                .check("database", () -> true)
                .check("redis", () -> true)
                .check("storage", () -> true);
        HealthService.Result result = health.report();
        assertEquals(200, result.httpStatus());
        assertEquals("UP", result.body().get("status"));
        assertEquals("UP", componentStatus(result, "database"));
    }

    @Test
    void healthEndpoint_returns503_whenRedisDown() {
        HealthService health = new HealthService()
                .check("database", () -> true)
                .check("redis", () -> false) // RedisClient.isAvailable() == false
                .check("storage", () -> true);
        HealthService.Result result = health.report();
        assertEquals(503, result.httpStatus());
        assertEquals("DOWN", result.body().get("status"));
        assertEquals("DOWN", componentStatus(result, "redis"));
        assertEquals("UP", componentStatus(result, "storage"));
    }

    @Test
    void throwingCheckCountsAsDown() {
        HealthService health = new HealthService()
                .check("storage", () -> {
                    throw new RuntimeException("disk exploded");
                });
        assertEquals(503, health.report().httpStatus());
    }
}
