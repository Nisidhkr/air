package p2p.agent;

import java.nio.file.Path;
import java.util.Map;

/**
 * Central port and path contract for the Fylo Local Agent.
 *
 * <p>The first agent phase keeps the existing LAN control plane and PeerLink
 * transfer engine intact, while making the browser-facing API explicit:
 * Next.js talks to {@code http://127.0.0.1:7000/agent/*} by default.
 */
public record AgentConfig(
        String agentHost,
        int agentPort,
        int webSocketPort,
        Path dataDir,
        Path downloadsDir,
        String mdnsServiceType,
        int peerLinkDynamicPortStart,
        int peerLinkDynamicPortEnd) {

    public static final String DEFAULT_AGENT_HOST = "127.0.0.1";
    public static final int DEFAULT_AGENT_PORT = 7000;
    public static final String DEFAULT_MDNS_SERVICE_TYPE = "_fylo._tcp.local.";
    public static final String AGENT_VERSION = "1.0.0";
    public static final int PEERLINK_DYNAMIC_PORT_START = 49152;
    public static final int PEERLINK_DYNAMIC_PORT_END = 65535;

    public AgentConfig {
        if (agentHost == null || agentHost.isBlank()) {
            agentHost = DEFAULT_AGENT_HOST;
        }
        if (agentPort < 0 || agentPort > 65535) {
            throw new IllegalArgumentException("agentPort must be between 0 and 65535");
        }
        int effectiveWebSocketPort = webSocketPort;
        if (effectiveWebSocketPort < 0 || effectiveWebSocketPort > 65535) {
            throw new IllegalArgumentException("webSocketPort must be between 0 and 65535");
        }
        if (dataDir == null) {
            throw new IllegalArgumentException("dataDir is required");
        }
        if (downloadsDir == null) {
            throw new IllegalArgumentException("downloadsDir is required");
        }
        if (mdnsServiceType == null || mdnsServiceType.isBlank()) {
            mdnsServiceType = DEFAULT_MDNS_SERVICE_TYPE;
        }
        if (peerLinkDynamicPortStart < 1 || peerLinkDynamicPortEnd > 65535
                || peerLinkDynamicPortStart >= peerLinkDynamicPortEnd) {
            throw new IllegalArgumentException("invalid PeerLink dynamic port range");
        }
    }

    public static AgentConfig fromEnvironment(String[] args) {
        return fromEnvironment(args, System.getenv());
    }

    static AgentConfig fromEnvironment(String[] args, Map<String, String> env) {
        int port = parsePort(firstPresent(
                args != null && args.length > 0 ? args[0] : null,
                env.get("FYLO_AGENT_PORT"),
                env.get("PORT")), DEFAULT_AGENT_PORT);
        String host = firstPresent(env.get("FYLO_AGENT_HOST"), DEFAULT_AGENT_HOST);
        int wsPort = parsePort(env.get("FYLO_AGENT_WS_PORT"), port == 0 ? 0 : port + 1);

        Path dataDir = Path.of(firstPresent(
                env.get("FYLO_DATA_DIR"),
                System.getProperty("peerlink.data.dir"),
                System.getProperty("user.home") + "/.peerlink"));
        Path downloadsDir = Path.of(firstPresent(
                env.get("FYLO_DOWNLOADS_DIR"),
                System.getProperty("peerlink.downloads.dir"),
                System.getProperty("user.home") + "/Downloads/PeerLink"));

        return new AgentConfig(host, port, wsPort, dataDir, downloadsDir,
                DEFAULT_MDNS_SERVICE_TYPE, PEERLINK_DYNAMIC_PORT_START,
                PEERLINK_DYNAMIC_PORT_END);
    }

    public static AgentConfig forPort(int port) {
        AgentConfig defaults = fromEnvironment(new String[0]);
        return defaults.withAgentPort(port);
    }

    public static AgentConfig forTest(int port, Path dataDir, Path downloadsDir) {
        return new AgentConfig(DEFAULT_AGENT_HOST, port, port == 0 ? 0 : port + 1,
                dataDir, downloadsDir, DEFAULT_MDNS_SERVICE_TYPE,
                PEERLINK_DYNAMIC_PORT_START, PEERLINK_DYNAMIC_PORT_END);
    }

    public AgentConfig withAgentPort(int port) {
        return new AgentConfig(agentHost, port, port == 0 ? 0 : port + 1,
                dataDir, downloadsDir, mdnsServiceType,
                peerLinkDynamicPortStart, peerLinkDynamicPortEnd);
    }

    public AgentConfig withBoundPorts(int boundAgentPort, int boundWebSocketPort) {
        return new AgentConfig(agentHost, boundAgentPort, boundWebSocketPort,
                dataDir, downloadsDir, mdnsServiceType,
                peerLinkDynamicPortStart, peerLinkDynamicPortEnd);
    }

    private static int parsePort(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(raw.trim());
    }

    private static String firstPresent(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

