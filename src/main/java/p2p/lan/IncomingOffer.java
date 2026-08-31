package p2p.lan;

import java.util.List;

/**
 * A pending "X wants to send you N files" request awaiting user approval
 * (or auto-approval for trusted devices).
 */
public final class IncomingOffer {

    public enum State { PENDING, ACCEPTED, REJECTED }

    private final String offerId;
    private final String senderDeviceId;
    private final String senderName;
    private final String senderOs;
    private final String senderHost;
    private final int senderApiPort;
    private final List<LanMessages.OfferFile> files;
    private final long createdAtEpochMs = System.currentTimeMillis();
    private volatile State state = State.PENDING;

    public IncomingOffer(String offerId, String senderDeviceId, String senderName, String senderOs,
                         String senderHost, int senderApiPort, List<LanMessages.OfferFile> files) {
        this.offerId = offerId;
        this.senderDeviceId = senderDeviceId;
        this.senderName = senderName;
        this.senderOs = senderOs;
        this.senderHost = senderHost;
        this.senderApiPort = senderApiPort;
        this.files = List.copyOf(files);
    }

    public String offerId() {
        return offerId;
    }

    public String senderDeviceId() {
        return senderDeviceId;
    }

    public String senderName() {
        return senderName;
    }

    public String senderOs() {
        return senderOs;
    }

    public String senderHost() {
        return senderHost;
    }

    public int senderApiPort() {
        return senderApiPort;
    }

    public List<LanMessages.OfferFile> files() {
        return files;
    }

    public long createdAtEpochMs() {
        return createdAtEpochMs;
    }

    public long totalBytes() {
        return files.stream().mapToLong(LanMessages.OfferFile::size).sum();
    }

    public State state() {
        return state;
    }

    void setState(State state) {
        this.state = state;
    }
}
