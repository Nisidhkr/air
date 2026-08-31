package p2p.plan;

/**
 * A request exceeded the caller's plan. The message is user-safe; the API
 * layer maps this to HTTP 403 with {@code code:"plan_limit"} so clients can
 * render an upgrade prompt.
 */
public final class PlanLimitException extends java.io.IOException {

    private final String limit;

    public PlanLimitException(String limit, String message) {
        super(message);
        this.limit = limit;
    }

    /** Machine-readable limit name, e.g. "max_file_size", "max_active_links". */
    public String limit() {
        return limit;
    }
}
