package p2p.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Dependency-free Prometheus metrics registry, exposed at {@code /metrics}
 * in text exposition format 0.0.4 (what Prometheus scrapes). Counters are
 * {@link LongAdder}s (uncontended under virtual-thread swarms); gauges are
 * sampled at scrape time via {@link LongSupplier} callbacks, so hot paths
 * never pay for observation.
 *
 * <p>Series naming: pass the full series including labels, e.g.
 * {@code counter("fylo_http_requests_total{route=\"api\",status=\"200\"}")}.
 * JVM gauges (heap, threads) are registered by the composition root.
 */
public final class Metrics {

    /** Prometheus-convention duration buckets (seconds). */
    private static final double[] DURATION_BUCKETS = {1, 5, 15, 60, 300, 900};

    private static final class Histogram {
        final LongAdder[] buckets = new LongAdder[DURATION_BUCKETS.length + 1];
        final LongAdder count = new LongAdder();
        final java.util.concurrent.atomic.DoubleAdder sum =
                new java.util.concurrent.atomic.DoubleAdder();

        Histogram() {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new LongAdder();
            }
        }
    }

    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongSupplier> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Histogram> histograms = new ConcurrentHashMap<>();

    public void increment(String series) {
        add(series, 1);
    }

    /** Records one observation into a cumulative histogram (seconds). */
    public void observeDurationSeconds(String name, double seconds) {
        Histogram histogram = histograms.computeIfAbsent(name, n -> new Histogram());
        for (int i = 0; i < DURATION_BUCKETS.length; i++) {
            if (seconds <= DURATION_BUCKETS[i]) {
                histogram.buckets[i].increment();
            }
        }
        histogram.buckets[DURATION_BUCKETS.length].increment(); // +Inf
        histogram.count.increment();
        histogram.sum.add(seconds);
    }

    public void add(String series, long delta) {
        counters.computeIfAbsent(series, s -> new LongAdder()).add(delta);
    }

    /** Registers a gauge sampled at scrape time. */
    public void gauge(String series, LongSupplier supplier) {
        gauges.put(series, supplier);
    }

    public String render() {
        StringBuilder out = new StringBuilder(1024);
        counters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.append(e.getKey()).append(' ')
                        .append(e.getValue().sum()).append('\n'));
        gauges.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.append(e.getKey()).append(' ')
                        .append(e.getValue().getAsLong()).append('\n'));
        histograms.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    String name = e.getKey();
                    Histogram h = e.getValue();
                    for (int i = 0; i < DURATION_BUCKETS.length; i++) {
                        out.append(name).append("_bucket{le=\"")
                                .append(trimTrailingZero(DURATION_BUCKETS[i]))
                                .append("\"} ").append(h.buckets[i].sum()).append('\n');
                    }
                    out.append(name).append("_bucket{le=\"+Inf\"} ")
                            .append(h.buckets[DURATION_BUCKETS.length].sum()).append('\n');
                    out.append(name).append("_sum ").append(h.sum.sum()).append('\n');
                    out.append(name).append("_count ").append(h.count.sum()).append('\n');
                });
        return out.toString();
    }

    private static String trimTrailingZero(double value) {
        return value == Math.floor(value) ? Long.toString((long) value) : Double.toString(value);
    }

    /** Handler for the /metrics scrape endpoint. */
    public HttpHandler handler() {
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] body = render().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type",
                        "text/plain; version=0.0.4; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        };
    }

    /** Standard JVM gauges every deployment wants on a dashboard. */
    public void registerJvmGauges() {
        Runtime runtime = Runtime.getRuntime();
        gauge("fylo_jvm_heap_used_bytes", () -> runtime.totalMemory() - runtime.freeMemory());
        gauge("fylo_jvm_heap_max_bytes", runtime::maxMemory);
        gauge("fylo_jvm_available_processors", runtime::availableProcessors);
    }
}
