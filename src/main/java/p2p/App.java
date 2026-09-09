package p2p;

/**
 * Compatibility entry point. The local runtime is now modeled as
 * {@link p2p.agent.FyloLocalAgent}.
 */
public class App {
    public static void main(String[] args) {
        p2p.agent.FyloLocalAgent.main(args);
    }
}
