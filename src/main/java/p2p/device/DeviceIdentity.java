package p2p.device;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * This device's stable identity, persisted in {@code identity.properties}
 * under the PeerLink data directory so the device keeps the same ID (and
 * therefore its trusted/recent status on peers) across restarts.
 */
public final class DeviceIdentity {

    private final String deviceId;
    private final String name;
    private final String os;
    private final String deviceType;

    private DeviceIdentity(String deviceId, String name, String os, String deviceType) {
        this.deviceId = deviceId;
        this.name = name;
        this.os = os;
        this.deviceType = deviceType;
    }

    public static DeviceIdentity loadOrCreate(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("identity.properties");
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
        }
        String id = props.getProperty("deviceId");
        String name = props.getProperty("name");
        if (id == null || name == null) {
            id = UUID.randomUUID().toString();
            name = defaultName();
            props.setProperty("deviceId", id);
            props.setProperty("name", name);
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "PeerLink device identity");
            }
        }
        return new DeviceIdentity(id, name, osName(), deviceType());
    }

    private static String defaultName() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) {
                return host;
            }
        } catch (IOException ignored) {
        }
        return System.getProperty("user.name", "PeerLink") + "'s device";
    }

    private static String osName() {
        return System.getProperty("os.name", "Unknown");
    }

    private static String deviceType() {
        String os = osName().toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return "macbook";
        }
        if (os.contains("win")) {
            return "windows-pc";
        }
        return "linux-pc";
    }

    public String deviceId() {
        return deviceId;
    }

    public String name() {
        return name;
    }

    public String os() {
        return os;
    }

    public String type() {
        return deviceType;
    }
}
