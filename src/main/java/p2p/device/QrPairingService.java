package p2p.device;

import p2p.security.TransferTokens;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * QR / code pairing for the Fylo device ecosystem ("verify both devices are
 * mine"). Device A displays a short-lived one-time pairing secret (rendered
 * as a QR by the client, or read out as a 6-char code); device B presents it
 * to A over the LAN control plane. A valid presentation proves the same
 * person controls both devices, and both sides mark each other trusted —
 * after which offers between them auto-accept with no approval popup.
 */
public final class QrPairingService {

    /** What device A shows. {@code payload} is the QR content. */
    public record PairingOffer(String pairingId, String secret, long expiresAtEpochMs,
                               String payload) {
    }

    private record Pending(String secret, long expiresAtEpochMs) {
    }

    private static final long PAIRING_TTL_MS = 2 * 60_000;

    private final DeviceIdentity identity;
    private final DeviceRegistry registry;
    private final int apiPort;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    public QrPairingService(DeviceIdentity identity, DeviceRegistry registry, int apiPort) {
        this.identity = identity;
        this.registry = registry;
        this.apiPort = apiPort;
    }

    /** Starts a pairing window on this device. */
    public PairingOffer start() {
        String pairingId = UUID.randomUUID().toString();
        String secret = TransferTokens.generate();
        long expiresAt = System.currentTimeMillis() + PAIRING_TTL_MS;
        pending.put(pairingId, new Pending(secret, expiresAt));
        // The QR embeds how to reach us and the one-time proof.
        String payload = "fylo://pair/" + pairingId + "/" + secret
                + "?device=" + identity.deviceId() + "&port=" + apiPort;
        System.out.println("event=agent.pair.start pairingId=" + pairingId
                + " expiresAtEpochMs=" + expiresAt);
        return new PairingOffer(pairingId, secret, expiresAt, payload);
    }

    /**
     * The other device presented our pairing secret (with its own identity,
     * relayed by the API layer). One-time: the entry is consumed atomically.
     * On success the presenting device becomes trusted.
     */
    public boolean complete(String pairingId, String presentedSecret, String peerDeviceId,
                            String peerName, String peerOs, String peerType,
                            String peerHost, int peerApiPort) {
        Pending entry = pending.remove(pairingId);
        if (entry == null || entry.expiresAtEpochMs() < System.currentTimeMillis()
                || !TransferTokens.matches(entry.secret(), presentedSecret)) {
            return false;
        }
        registry.upsertOnline(peerDeviceId, peerName, peerOs, peerType, peerHost, peerApiPort);
        registry.setTrusted(peerDeviceId, true);
        System.out.println("event=agent.pair.complete peerDeviceId=" + peerDeviceId
                + " peerHost=" + peerHost + " peerApiPort=" + peerApiPort);
        return true;
    }

    /** Parses a scanned QR payload on the joining device's side. */
    public static Optional<Map<String, String>> parsePayload(String payload) {
        if (payload == null
                || (!payload.startsWith("fylo://pair/")
                && !payload.startsWith("air://pair/"))) {
            return Optional.empty();
        }
        try {
            String prefix = payload.startsWith("fylo://pair/") ? "fylo://pair/" : "air://pair/";
            String rest = payload.substring(prefix.length());
            int q = rest.indexOf('?');
            String[] idAndSecret = (q == -1 ? rest : rest.substring(0, q)).split("/");
            if (idAndSecret.length != 2) {
                return Optional.empty();
            }
            Map<String, String> parsed = new java.util.HashMap<>();
            parsed.put("pairingId", idAndSecret[0]);
            parsed.put("secret", idAndSecret[1]);
            if (q != -1) {
                for (String param : rest.substring(q + 1).split("&")) {
                    int eq = param.indexOf('=');
                    if (eq > 0) {
                        parsed.put(param.substring(0, eq), param.substring(eq + 1));
                    }
                }
            }
            return Optional.of(parsed);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
