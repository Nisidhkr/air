package p2p.device;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import p2p.agent.AgentConfig;

import java.io.Closeable;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zero-configuration LAN discovery via mDNS/DNS-SD (the protocol behind
 * Bonjour on macOS/iOS and NSD on Android), using the pure-Java JmDNS
 * implementation so the same code runs on Windows, Linux, and macOS.
 *
 * <p>Each node registers {@code _fylo._tcp.local.} with its
 * API port and identity in TXT records, and browses for the same type.
 * Discovered peers go into the {@link DeviceRegistry}; departure events and
 * the {@link HeartbeatService} take them offline again.
 *
 * <p>Discovery is best-effort: on networks where multicast is filtered
 * (some corporate Wi-Fi, WSL2 NAT), the rest of the application keeps working
 * — devices just have to be reached via the existing invite-code flow.
 */
public final class DeviceDiscoveryService implements Closeable {

    public static final String MDNS_SERVICE_TYPE = AgentConfig.DEFAULT_MDNS_SERVICE_TYPE;

    private final DeviceIdentity identity;
    private final DeviceRegistry registry;
    private final int apiPort;
    /** mDNS service name -> deviceId, to resolve removals (no TXT on remove events). */
    private final ConcurrentHashMap<String, String> serviceNameToDeviceId = new ConcurrentHashMap<>();
    private volatile JmDNS jmdns;

    public DeviceDiscoveryService(DeviceIdentity identity, DeviceRegistry registry, int apiPort) {
        this.identity = identity;
        this.registry = registry;
        this.apiPort = apiPort;
    }

    public void start() throws IOException {
        InetAddress bindAddress = pickSiteLocalAddress();
        jmdns = bindAddress != null ? JmDNS.create(bindAddress) : JmDNS.create();
        System.out.println("event=mdns.start serviceType=" + MDNS_SERVICE_TYPE
                + " bindAddress=" + (bindAddress == null ? "default" : bindAddress.getHostAddress())
                + " port=" + apiPort);

        Map<String, String> txt = new HashMap<>();
        txt.put("id", identity.deviceId());
        txt.put("name", identity.name());
        txt.put("os", identity.os());
        txt.put("type", identity.type());
        txt.put("version", AgentConfig.AGENT_VERSION);
        txt.put("capabilities", "SEND,RECEIVE,ECOSYSTEM");
        String serviceName = identity.name() + "-" + identity.deviceId().substring(0, 8);
        jmdns.registerService(ServiceInfo.create(MDNS_SERVICE_TYPE, serviceName, apiPort, 0, 0, txt));
        System.out.println("event=mdns.registered serviceType=" + MDNS_SERVICE_TYPE
                + " serviceName=" + serviceName
                + " deviceId=" + identity.deviceId()
                + " port=" + apiPort);

        jmdns.addServiceListener(MDNS_SERVICE_TYPE, new ServiceListener() {
            @Override
            public void serviceAdded(ServiceEvent event) {
                // Ask for resolution; serviceResolved fires with addresses + TXT.
                event.getDNS().requestServiceInfo(event.getType(), event.getName(), 1000);
            }

            @Override
            public void serviceResolved(ServiceEvent event) {
                onResolved(event.getInfo());
            }

            @Override
            public void serviceRemoved(ServiceEvent event) {
                String deviceId = serviceNameToDeviceId.get(event.getName());
                if (deviceId != null) {
                    registry.markOffline(deviceId);
                    System.out.println("event=mdns.removed peerDeviceId=" + deviceId
                            + " serviceName=" + event.getName());
                }
            }
        });
    }

    private void onResolved(ServiceInfo info) {
        String deviceId = info.getPropertyString("id");
        if (deviceId == null || deviceId.equals(identity.deviceId())) {
            return; // unparseable or our own registration echoed back
        }
        Inet4Address[] addresses = info.getInet4Addresses();
        if (addresses.length == 0) {
            return;
        }
        serviceNameToDeviceId.put(info.getName(), deviceId);
        System.out.println("event=mdns.discovered peerDeviceId=" + deviceId
                + " host=" + addresses[0].getHostAddress()
                + " port=" + info.getPort()
                + " serviceName=" + info.getName());
        registry.upsertOnline(
                deviceId,
                orDefault(info.getPropertyString("name"), info.getName()),
                orDefault(info.getPropertyString("os"), "Unknown"),
                orDefault(info.getPropertyString("type"), "unknown"),
                addresses[0].getHostAddress(),
                info.getPort());
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * Prefer a site-local IPv4 interface so the advertised address is the one
     * peers can actually connect to (not loopback or a virtual adapter).
     */
    private static InetAddress pickSiteLocalAddress() {
        String configuredAddress = System.getenv("FYLO_MDNS_ADDRESS");
        if (configuredAddress != null && !configuredAddress.isBlank()) {
            try {
                InetAddress address = InetAddress.getByName(configuredAddress.trim());
                if (address instanceof Inet4Address) {
                    return address;
                }
            } catch (IOException ignored) {
            }
        }
        try {
            String defaultInterface = defaultRouteInterface();
            if (defaultInterface != null) {
                InetAddress address = firstUsableAddress(NetworkInterface.getByName(defaultInterface));
                if (address != null) {
                    return address;
                }
            }
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || !nic.supportsMulticast() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                InetAddress address = firstUsableAddress(nic);
                if (address != null) {
                    return address;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static InetAddress firstUsableAddress(NetworkInterface nic) throws IOException {
        if (nic == null || !nic.isUp() || !nic.supportsMulticast()
                || nic.isLoopback() || nic.isVirtual()) {
            return null;
        }
        var addresses = nic.getInetAddresses();
        while (addresses.hasMoreElements()) {
            InetAddress address = addresses.nextElement();
            if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                return address;
            }
        }
        return null;
    }

    private static String defaultRouteInterface() {
        Path routeFile = Path.of("/proc/net/route");
        if (!Files.isReadable(routeFile)) {
            return null;
        }
        try {
            var lines = Files.readAllLines(routeFile);
            for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length > 1 && fields[1].equals("00000000")) {
                    return fields[0];
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        JmDNS instance = jmdns;
        if (instance != null) {
            instance.unregisterAllServices();
            instance.close();
        }
    }
}
