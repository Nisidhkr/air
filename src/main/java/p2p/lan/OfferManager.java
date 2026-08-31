package p2p.lan;

import p2p.device.DeviceRegistry;
import p2p.transfer.FileReceiver;
import p2p.transfer.TransferManager;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Receiver-side approval and execution of LAN offers.
 *
 * <p>Every offer requires explicit user approval ("Nisidh Android wants to
 * send 5 files (12.4 GB) — Accept / Reject") unless the sender was previously
 * marked trusted, in which case it is auto-accepted. Accepting enqueues one
 * {@link TransferManager} receive per file, pulling from the sender over the
 * binary protocol with the offer's tokens; relative paths are sanitized
 * segment-by-segment so a hostile sender cannot escape the downloads folder.
 */
public final class OfferManager {

    private final DeviceRegistry registry;
    private final TransferManager transferManager;
    private final ControlPlaneClient controlPlane;
    private final Path downloadsDir;
    private final ConcurrentHashMap<String, IncomingOffer> offers = new ConcurrentHashMap<>();

    public OfferManager(DeviceRegistry registry, TransferManager transferManager,
                        ControlPlaneClient controlPlane, Path downloadsDir) {
        this.registry = registry;
        this.transferManager = transferManager;
        this.controlPlane = controlPlane;
        this.downloadsDir = downloadsDir;
    }

    /**
     * Registers an offer arriving from {@code senderHost}. The host comes from
     * the TCP connection, not the payload, so a device cannot impersonate
     * another address. Returns the offer (auto-accepted if the sender is trusted).
     */
    public IncomingOffer register(LanMessages.OfferRequest request, String senderHost) {
        IncomingOffer offer = new IncomingOffer(request.offerId(), request.deviceId(),
                request.deviceName(), request.os(), senderHost, request.apiPort(), request.files());
        offers.put(offer.offerId(), offer);

        // An incoming offer proves the device is alive at this address.
        registry.upsertOnline(request.deviceId(), request.deviceName(), request.os(),
                request.deviceType(), senderHost, request.apiPort());

        if (registry.isTrusted(request.deviceId())) {
            accept(offer.offerId(), false);
        }
        return offer;
    }

    public List<IncomingOffer> pending() {
        return offers.values().stream()
                .filter(o -> o.state() == IncomingOffer.State.PENDING)
                .sorted(Comparator.comparingLong(IncomingOffer::createdAtEpochMs))
                .toList();
    }

    public boolean accept(String offerId, boolean trustSender) {
        IncomingOffer offer = offers.get(offerId);
        if (offer == null || offer.state() != IncomingOffer.State.PENDING) {
            return false;
        }
        offer.setState(IncomingOffer.State.ACCEPTED);
        if (trustSender) {
            registry.setTrusted(offer.senderDeviceId(), true);
        }

        AtomicInteger remaining = new AtomicInteger(offer.files().size());
        AtomicBoolean anyFailed = new AtomicBoolean(false);
        for (LanMessages.OfferFile file : offer.files()) {
            Path targetDir = resolveTargetDirectory(file.relativePath(), file.name());
            TransferManager.ReceiveSpec spec = new TransferManager.ReceiveSpec(
                    offer.senderHost(), file.port(), file.token(), targetDir, file.name());
            transferManager.enqueueReceive(spec, file.size(), offer.senderName(), finished -> {
                if (finished.status() != TransferManager.Status.COMPLETED) {
                    anyFailed.set(true);
                }
                if (remaining.decrementAndGet() == 0) {
                    controlPlane.postOfferResult(offer.senderHost(), offer.senderApiPort(),
                            new LanMessages.OfferResult(offer.offerId(),
                                    anyFailed.get() ? LanMessages.OfferResult.FAILED
                                            : LanMessages.OfferResult.COMPLETED));
                }
            });
        }
        controlPlane.postOfferResult(offer.senderHost(), offer.senderApiPort(),
                new LanMessages.OfferResult(offer.offerId(), LanMessages.OfferResult.ACCEPTED));
        return true;
    }

    public boolean reject(String offerId) {
        IncomingOffer offer = offers.get(offerId);
        if (offer == null || offer.state() != IncomingOffer.State.PENDING) {
            return false;
        }
        offer.setState(IncomingOffer.State.REJECTED);
        controlPlane.postOfferResult(offer.senderHost(), offer.senderApiPort(),
                new LanMessages.OfferResult(offer.offerId(), LanMessages.OfferResult.REJECTED));
        return true;
    }

    /**
     * Builds the download directory for a file, honoring folder structure from
     * the offer while sanitizing every path segment (no "..", no absolute
     * paths, no control characters).
     */
    private Path resolveTargetDirectory(String relativePath, String fileName) {
        Path dir = downloadsDir;
        if (relativePath != null && !relativePath.isBlank()) {
            String[] segments = relativePath.replace('\\', '/').split("/");
            // Last segment is the file itself; intermediate segments are folders.
            for (int i = 0; i < segments.length - 1; i++) {
                String segment = FileReceiver.sanitizeFilename(segments[i]);
                if (!segment.equals("unnamed-file")) {
                    dir = dir.resolve(segment);
                }
            }
        }
        return dir;
    }
}
