package p2p.lan;

import p2p.device.DeviceIdentity;
import p2p.device.DeviceInfo;
import p2p.device.DeviceRegistry;
import p2p.service.FileSharer;
import p2p.transfer.TransferManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sender side of LAN mode: turns already-shared files (the same
 * {@link FileSharer} shares the invite-code flow uses) into an offer for a
 * discovered device, and tracks the outcome reported back by the receiver.
 */
public final class LanShareService {

    private final DeviceIdentity identity;
    private final DeviceRegistry registry;
    private final FileSharer fileSharer;
    private final TransferManager transferManager;
    private final ControlPlaneClient controlPlane;
    private final int apiPort;
    /** offerId -> transfer ids representing this offer in the queue UI. */
    private final ConcurrentHashMap<String, List<String>> outgoingOffers = new ConcurrentHashMap<>();

    public LanShareService(DeviceIdentity identity, DeviceRegistry registry, FileSharer fileSharer,
                           TransferManager transferManager, ControlPlaneClient controlPlane,
                           int apiPort) {
        this.identity = identity;
        this.registry = registry;
        this.fileSharer = fileSharer;
        this.transferManager = transferManager;
        this.controlPlane = controlPlane;
        this.apiPort = apiPort;
    }

    /**
     * Offers the shares behind {@code sharePorts} to {@code deviceId}.
     * The receiver pulls the data directly from this machine's FileSenders —
     * nothing is uploaded anywhere.
     */
    public String send(String deviceId, List<Integer> sharePorts) throws IOException {
        DeviceInfo device = registry.find(deviceId)
                .orElseThrow(() -> new IOException("Unknown device: " + deviceId));
        if (device.host() == null || device.host().isBlank() || device.apiPort() <= 0) {
            throw new IOException("Device has no reachable address yet: " + device.name());
        }

        List<LanMessages.OfferFile> files = new ArrayList<>();
        for (int port : sharePorts) {
            FileSharer.ShareInfo info = fileSharer.shareInfo(port)
                    .orElseThrow(() -> new IOException("No active share on port " + port));
            files.add(new LanMessages.OfferFile(
                    info.fileName(), info.size(), info.port(), info.token(), info.relativePath()));
        }
        if (files.isEmpty()) {
            throw new IOException("Nothing to send");
        }

        String offerId = UUID.randomUUID().toString();
        List<String> transferIds = new ArrayList<>();
        for (LanMessages.OfferFile file : files) {
            transferIds.add(transferManager
                    .trackOutgoingSend(file.name(), file.size(), device.name()).id());
        }
        outgoingOffers.put(offerId, transferIds);

        try {
            controlPlane.postOffer(device.host(), device.apiPort(),
                    new LanMessages.OfferRequest(offerId, identity.deviceId(), identity.name(),
                            identity.os(), identity.type(), apiPort, files));
        } catch (IOException e) {
            transferIds.forEach(id -> transferManager.updateStatus(id, TransferManager.Status.FAILED));
            outgoingOffers.remove(offerId);
            throw new IOException("Could not reach " + device.name() + ": " + e.getMessage(), e);
        }
        return offerId;
    }

    /** Receiver's verdict / completion report for one of our offers. */
    public void onOfferResult(LanMessages.OfferResult result) {
        List<String> transferIds = outgoingOffers.get(result.offerId());
        if (transferIds == null) {
            return;
        }
        TransferManager.Status status = switch (result.status()) {
            case LanMessages.OfferResult.ACCEPTED -> TransferManager.Status.ACTIVE;
            case LanMessages.OfferResult.REJECTED -> TransferManager.Status.REJECTED;
            case LanMessages.OfferResult.COMPLETED -> TransferManager.Status.COMPLETED;
            case LanMessages.OfferResult.FAILED -> TransferManager.Status.FAILED;
            default -> null;
        };
        if (status != null) {
            transferIds.forEach(id -> transferManager.updateStatus(id, status));
        }
        if (status == TransferManager.Status.COMPLETED || status == TransferManager.Status.REJECTED
                || status == TransferManager.Status.FAILED) {
            outgoingOffers.remove(result.offerId());
        }
    }
}
