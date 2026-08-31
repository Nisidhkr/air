package p2p.device;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of every device this node has ever seen. Live presence
 * comes from {@link DeviceDiscoveryService} and {@link HeartbeatService};
 * known devices and their trusted flags are persisted to
 * {@code devices.properties} so "Recent devices" and pairing survive restarts.
 */
public final class DeviceRegistry {

    private static final class Entry {
        final String deviceId;
        volatile String name;
        volatile String os;
        volatile String deviceType;
        volatile String host;
        volatile int apiPort;
        volatile long lastSeenEpochMs;
        volatile boolean online;
        volatile boolean trusted;

        Entry(String deviceId) {
            this.deviceId = deviceId;
        }

        DeviceInfo snapshot() {
            return new DeviceInfo(deviceId, name, os, deviceType, host, apiPort,
                    lastSeenEpochMs, online, trusted);
        }
    }

    private final ConcurrentHashMap<String, Entry> devices = new ConcurrentHashMap<>();
    private final Path persistFile;

    public DeviceRegistry(Path dataDir) {
        this.persistFile = dataDir.resolve("devices.properties");
        load();
    }

    /** Called when discovery or an incoming request proves the device is alive now. */
    public void upsertOnline(String deviceId, String name, String os, String deviceType,
                             String host, int apiPort) {
        Entry entry = devices.computeIfAbsent(deviceId, Entry::new);
        entry.name = name;
        entry.os = os;
        entry.deviceType = deviceType;
        entry.host = host;
        entry.apiPort = apiPort;
        entry.lastSeenEpochMs = System.currentTimeMillis();
        entry.online = true;
        persist();
    }

    public void markSeen(String deviceId) {
        Entry entry = devices.get(deviceId);
        if (entry != null) {
            entry.lastSeenEpochMs = System.currentTimeMillis();
            entry.online = true;
        }
    }

    public void markOffline(String deviceId) {
        Entry entry = devices.get(deviceId);
        if (entry != null) {
            entry.online = false;
        }
    }

    public void setTrusted(String deviceId, boolean trusted) {
        Entry entry = devices.get(deviceId);
        if (entry != null) {
            entry.trusted = trusted;
            persist();
        }
    }

    public boolean isTrusted(String deviceId) {
        Entry entry = devices.get(deviceId);
        return entry != null && entry.trusted;
    }

    public Optional<DeviceInfo> find(String deviceId) {
        Entry entry = devices.get(deviceId);
        return entry == null ? Optional.empty() : Optional.of(entry.snapshot());
    }

    /** All known devices, online first, then most recently seen. */
    public List<DeviceInfo> snapshots() {
        return devices.values().stream()
                .map(Entry::snapshot)
                .sorted(Comparator.comparing(DeviceInfo::online).reversed()
                        .thenComparing(Comparator.comparingLong(DeviceInfo::lastSeenEpochMs).reversed()))
                .toList();
    }

    private void load() {
        if (!Files.exists(persistFile)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(persistFile)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("Could not load device registry: " + e.getMessage());
            return;
        }
        for (String id : props.stringPropertyNames()) {
            // id = name|os|type|host|port|lastSeen|trusted
            String[] parts = props.getProperty(id).split("\\|", -1);
            if (parts.length != 7) {
                continue;
            }
            Entry entry = new Entry(id);
            entry.name = parts[0];
            entry.os = parts[1];
            entry.deviceType = parts[2];
            entry.host = parts[3];
            entry.apiPort = Integer.parseInt(parts[4]);
            entry.lastSeenEpochMs = Long.parseLong(parts[5]);
            entry.trusted = Boolean.parseBoolean(parts[6]);
            entry.online = false; // presence must be re-proven each run
            devices.put(id, entry);
        }
    }

    private synchronized void persist() {
        Properties props = new Properties();
        for (Entry e : devices.values()) {
            props.setProperty(e.deviceId, String.join("|",
                    nullSafe(e.name), nullSafe(e.os), nullSafe(e.deviceType), nullSafe(e.host),
                    Integer.toString(e.apiPort), Long.toString(e.lastSeenEpochMs),
                    Boolean.toString(e.trusted)));
        }
        try {
            Files.createDirectories(persistFile.getParent());
            try (OutputStream out = Files.newOutputStream(persistFile)) {
                props.store(out, "PeerLink known devices");
            }
        } catch (IOException e) {
            System.err.println("Could not persist device registry: " + e.getMessage());
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.replace("|", "_");
    }
}
