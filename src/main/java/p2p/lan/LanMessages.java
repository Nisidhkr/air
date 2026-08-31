package p2p.lan;

import java.util.List;

/**
 * JSON messages of the LAN control plane (device-to-device HTTP). The data
 * plane is NOT here: files always travel over the same binary PeerLink
 * protocol as internet shares — an offer simply delivers the port + token
 * that an invite code would otherwise carry, so both modes share one engine.
 */
public final class LanMessages {

    private LanMessages() {
    }

    /** One file in an offer; port+token point at a FileSender on the sender. */
    public record OfferFile(String name, long size, int port, String token, String relativePath) {
    }

    /** POST /lan/offer — sender announces files it wants to push to this device. */
    public record OfferRequest(
            String offerId,
            String deviceId,
            String deviceName,
            String os,
            String deviceType,
            int apiPort,
            List<OfferFile> files) {
    }

    /** POST /lan/offer-result — receiver tells the sender what happened. */
    public record OfferResult(String offerId, String status) {
        public static final String ACCEPTED = "accepted";
        public static final String REJECTED = "rejected";
        public static final String COMPLETED = "completed";
        public static final String FAILED = "failed";
    }

    /**
     * POST /lan/hello — manual introduction for networks where mDNS multicast
     * is filtered (WSL2 NAT, guest Wi-Fi). The caller introduces itself; the
     * response is the callee's identity, so one round trip registers both
     * devices with each other.
     */
    public record Hello(String deviceId, String name, String os, String deviceType, int apiPort) {
    }
}
