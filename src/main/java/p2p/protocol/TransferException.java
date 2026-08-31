package p2p.protocol;

import java.io.IOException;

/**
 * A protocol-level failure reported by the remote peer (or detected locally),
 * carrying one of the {@code PeerLinkProtocol.ERR_*} codes.
 */
public class TransferException extends IOException {

    private final short code;

    public TransferException(short code, String message) {
        super(message);
        this.code = code;
    }

    public short code() {
        return code;
    }

    /** Errors like auth failure or a changed file are not fixed by reconnecting. */
    public boolean isRetryable() {
        return code == PeerLinkProtocol.ERR_INTERNAL;
    }

    @Override
    public String toString() {
        return "TransferException[code=" + code + ", message=" + getMessage() + "]";
    }
}
