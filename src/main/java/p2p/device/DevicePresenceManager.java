package p2p.device;

import java.io.Closeable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Online/offline policy on top of {@link HeartbeatService}: every device in
 * the registry is pinged periodically (on virtual threads, in parallel); one
 * failed ping is forgiven (a busy device or a dropped packet), two consecutive
 * misses mark the device offline. Any successful ping or discovery event
 * resets the count.
 */
public final class DevicePresenceManager implements Closeable {

    private static final int MISSES_BEFORE_OFFLINE = 2;

    private final DeviceRegistry registry;
    private final HeartbeatService heartbeat;
    private final long intervalSeconds;
    private final ConcurrentHashMap<String, AtomicInteger> missCounts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "device-presence");
        t.setDaemon(true);
        return t;
    });

    public DevicePresenceManager(DeviceRegistry registry, HeartbeatService heartbeat, long intervalSeconds) {
        this.registry = registry;
        this.heartbeat = heartbeat;
        this.intervalSeconds = intervalSeconds;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::sweep, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void sweep() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (DeviceInfo device : registry.snapshots()) {
                executor.submit(() -> check(device));
            }
        }
    }

    private void check(DeviceInfo device) {
        if (heartbeat.ping(device)) {
            missCounts.remove(device.deviceId());
            registry.markSeen(device.deviceId());
            return;
        }
        if (!device.online()) {
            return;
        }
        int misses = missCounts.computeIfAbsent(device.deviceId(), k -> new AtomicInteger())
                .incrementAndGet();
        if (misses >= MISSES_BEFORE_OFFLINE) {
            registry.markOffline(device.deviceId());
            missCounts.remove(device.deviceId());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
