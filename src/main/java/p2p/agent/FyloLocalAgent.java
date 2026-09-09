package p2p.agent;

import p2p.controller.FileController;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Headless Fylo Local Agent entry point.
 *
 * <p>This phase deliberately reuses the existing Java LAN backend rather than
 * moving packages wholesale. The agent lifecycle wraps the current
 * `FileController` composition root and exposes the browser-facing
 * `/agent/*` API mounted by that controller.
 */
public final class FyloLocalAgent implements AutoCloseable {

    private final AgentConfig config;
    private final FileController controller;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public FyloLocalAgent(AgentConfig config) throws IOException {
        this.config = config;
        this.controller = new FileController(config);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        System.out.println("event=agent.starting host=" + config.agentHost()
                + " port=" + config.agentPort()
                + " mdnsServiceType=" + config.mdnsServiceType());
        controller.start();
        System.out.println("event=agent.ready host=" + config.agentHost()
                + " port=" + controller.port()
                + " websocketPort=" + controller.webSocketPort());
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        System.out.println("event=agent.stopping port=" + controller.port());
        controller.stop();
        System.out.println("event=agent.stopped port=" + controller.port());
    }

    public FileController controller() {
        return controller;
    }

    @Override
    public void close() {
        stop();
    }

    public static void main(String[] args) {
        AgentConfig config;
        try {
            config = AgentConfig.fromEnvironment(args);
        } catch (RuntimeException e) {
            System.err.println("Invalid agent configuration: " + e.getMessage());
            return;
        }

        try {
            FyloLocalAgent agent = new FyloLocalAgent(config);
            agent.start();

            Runtime.getRuntime().addShutdownHook(new Thread(agent::stop, "fylo-agent-shutdown"));

            System.out.println("Fylo Local Agent API: http://" + config.agentHost()
                    + ":" + agent.controller().port() + "/agent/status");
            System.out.println("UI available at http://localhost:3000");
            System.out.println("Press Enter to stop the agent");

            int key = System.in.read();
            if (key == -1) {
                try {
                    Thread.currentThread().join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (IOException e) {
            System.err.println("Error starting Fylo Local Agent: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

