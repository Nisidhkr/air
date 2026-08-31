package p2p.transfer;

import java.time.Duration;

/**
 * Tuning knobs for a transfer. Presets encode the bandwidth-delay-product
 * reasoning for common network profiles:
 *
 * <ul>
 *   <li><b>LAN</b> (1-10 Gbps, &lt;1 ms RTT): BDP is tiny (1 Gbps x 1 ms =
 *       ~125 KB), so modest socket buffers already saturate the link and a
 *       single stream is enough; 2 connections only help when one stream is
 *       CPU- or disk-bound.</li>
 *   <li><b>WAN</b> (100-500 Mbps, ~40 ms RTT): BDP ~ 500 Mbps x 40 ms = 2.5 MB.
 *       4 MB buffers let the TCP window open fully; 4 parallel streams recover
 *       throughput lost to per-connection congestion-window collapse after loss.</li>
 *   <li><b>High latency</b> (satellite/intercontinental, 150-300 ms RTT):
 *       BDP ~ 200 Mbps x 300 ms = 7.5 MB. 16 MB buffers and 8 streams work
 *       around slow congestion-window growth; each loss event then only
 *       affects 1/8th of the aggregate window.</li>
 * </ul>
 *
 * Buffer sizes of 0 mean "leave the OS default" (modern Linux auto-tunes,
 * which often beats a fixed value).
 */
public record TransferConfig(
        int socketSendBufferBytes,
        int socketReceiveBufferBytes,
        int parallelConnections,
        int chunkSizeBytes,
        Duration connectTimeout,
        Duration idleTimeout,
        int maxRetries) {

    public static final int DEFAULT_CHUNK_SIZE = 4 << 20; // 4 MB checksum granularity

    public TransferConfig {
        if (parallelConnections < 1) {
            throw new IllegalArgumentException("parallelConnections must be >= 1");
        }
        if (chunkSizeBytes < 64 * 1024) {
            throw new IllegalArgumentException("chunkSizeBytes must be >= 64 KB");
        }
    }

    public static TransferConfig defaults() {
        return wan();
    }

    public static TransferConfig lan() {
        return new TransferConfig(0, 0, 2, DEFAULT_CHUNK_SIZE,
                Duration.ofSeconds(5), Duration.ofSeconds(30), 3);
    }

    public static TransferConfig wan() {
        return new TransferConfig(4 << 20, 4 << 20, 4, DEFAULT_CHUNK_SIZE,
                Duration.ofSeconds(10), Duration.ofSeconds(60), 5);
    }

    public static TransferConfig highLatency() {
        return new TransferConfig(16 << 20, 16 << 20, 8, DEFAULT_CHUNK_SIZE,
                Duration.ofSeconds(20), Duration.ofSeconds(120), 8);
    }

    public TransferConfig withParallelConnections(int connections) {
        return new TransferConfig(socketSendBufferBytes, socketReceiveBufferBytes, connections,
                chunkSizeBytes, connectTimeout, idleTimeout, maxRetries);
    }
}
