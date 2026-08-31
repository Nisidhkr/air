package p2p.share;

/**
 * MODE 3 — a pending "@user wants to send you a file" request. Carries the
 * connection details of the sender's live share; they are revealed to the
 * receiver only after an explicit accept.
 */
public record TransferRequest(
        String requestId,
        String fromUserId,
        String fromUsername,
        String toUserId,
        String fileName,
        long sizeBytes,
        int sharePort,
        String shareToken,
        State state,
        long createdAtEpochMs) {

    public enum State { PENDING, ACCEPTED, REJECTED, EXPIRED, COMPLETED }

    public TransferRequest withState(State newState) {
        return new TransferRequest(requestId, fromUserId, fromUsername, toUserId, fileName,
                sizeBytes, sharePort, shareToken, newState, createdAtEpochMs);
    }
}
