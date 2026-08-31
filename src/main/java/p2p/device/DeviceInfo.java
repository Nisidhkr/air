package p2p.device;

/**
 * Immutable snapshot of a known peer device, as served to the UI and used by
 * the LAN share service to address peers.
 */
public record DeviceInfo(
        String deviceId,
        String name,
        String os,
        String deviceType,
        String host,
        int apiPort,
        long lastSeenEpochMs,
        boolean online,
        boolean trusted) {
}
