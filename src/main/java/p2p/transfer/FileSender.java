package p2p.transfer;

import p2p.protocol.PeerLinkProtocol;
import p2p.protocol.TransferManifest;
import p2p.security.TransferTokens;
import p2p.util.Hashing;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;

import static p2p.protocol.PeerLinkProtocol.*;

/**
 * Serves one file to any number of concurrent clients over the PeerLink
 * binary protocol.
 *
 * <p><b>Throughput:</b> data is sent with {@link FileChannel#transferTo},
 * which on Linux becomes {@code sendfile(2)}: bytes move page-cache to socket
 * inside the kernel with no user-space copy and no per-buffer JNI crossing.
 * For a 40 GB file this saves ~80 GB of memcpy and the associated CPU/GC work
 * compared to a read/write loop.
 *
 * <p><b>Memory:</b> the sender holds no file data on the heap at all — only
 * per-connection frame buffers (&lt; 1 MB). A thousand concurrent downloads of
 * a 40 GB file would still use a few MB of heap.
 *
 * <p><b>Concurrency:</b> one virtual thread per connection (Java 21). Blocking
 * I/O code stays simple while idle connections cost ~1 KB instead of a platform
 * thread's 1 MB stack.
 */
public final class FileSender implements Closeable {

    /** Max bytes per transferTo call: keeps the loop responsive for activity tracking. */
    private static final long TRANSFER_SLICE_BYTES = 8L << 20;

    private final Path file;
    private final String expectedToken;
    private final TransferConfig config;
    private final long fileSize;
    private final FileChannel fileChannel;
    private final ServerSocketChannel serverChannel;
    private final CompletableFuture<TransferManifest> manifestFuture;
    private final UUID transferId = UUID.randomUUID();

    private final ExecutorService connectionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "filesender-watchdog");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<SocketChannel, AtomicLong> lastActivityNanos = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public FileSender(Path file, String expectedToken, TransferConfig config, int port) throws IOException {
        this(file, file.getFileName().toString(), expectedToken, config, port);
    }

    /**
     * @param displayName the filename advertised in the manifest — may differ
     *                    from the on-disk name (uploads are stored uuid-prefixed).
     */
    public FileSender(Path file, String displayName, String expectedToken, TransferConfig config,
                      int port) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Not a regular file: " + file);
        }
        this.file = file;
        this.expectedToken = expectedToken;
        this.config = config;
        this.fileSize = Files.size(file);
        this.fileChannel = FileChannel.open(file, StandardOpenOption.READ);
        this.serverChannel = ServerSocketChannel.open();
        this.serverChannel.bind(new InetSocketAddress(port));

        // Hashing 40 GB takes a while; do it in the background so offering a
        // file returns immediately. The first MANIFEST waits on the result.
        String filename = displayName;
        byte[] metadata = ("{\"name\":\"" + filename.replace("\"", "") + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        this.manifestFuture = CompletableFuture
                .supplyAsync(() -> Hashing.sha256Unchecked(file), connectionExecutor)
                .thenApply(hash -> new TransferManifest(
                        transferId, filename, fileSize, config.chunkSizeBytes(), hash, metadata));
    }

    public int port() throws IOException {
        return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
    }

    public UUID transferId() {
        return transferId;
    }

    /** Starts the accept loop; returns immediately. */
    public void start() {
        connectionExecutor.submit(this::acceptLoop);
        long idleNanos = config.idleTimeout().toNanos();
        watchdog.scheduleAtFixedRate(() -> closeIdleConnections(idleNanos),
                idleNanos, idleNanos / 2, TimeUnit.NANOSECONDS);
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                SocketChannel client = serverChannel.accept();
                connectionExecutor.submit(() -> handleClient(client));
            } catch (ClosedChannelException e) {
                return; // close() was called
            } catch (IOException e) {
                if (!closed) {
                    System.err.println("Accept failed on " + file.getFileName() + ": " + e.getMessage());
                }
            }
        }
    }

    private void closeIdleConnections(long idleNanos) {
        long now = System.nanoTime();
        lastActivityNanos.forEach((channel, last) -> {
            if (now - last.get() > idleNanos) {
                closeQuietly(channel);
            }
        });
    }

    private void handleClient(SocketChannel channel) {
        AtomicLong activity = new AtomicLong(System.nanoTime());
        lastActivityNanos.put(channel, activity);
        try (channel) {
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            // Control frames are tiny; without NODELAY, Nagle would delay them
            // behind unacked data. Bulk data is unaffected (always full segments).
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            if (config.socketSendBufferBytes() > 0) {
                channel.setOption(StandardSocketOptions.SO_SNDBUF, config.socketSendBufferBytes());
            }

            // 1. Authenticate before revealing anything about the file.
            Frame hello = readFrame(channel);
            if (hello.type() != MSG_HELLO
                    || !TransferTokens.matches(expectedToken, getString(hello.payload()))) {
                writeError(channel, ERR_AUTH_FAILED, "Invalid transfer token");
                return;
            }
            activity.set(System.nanoTime());

            // 2. Send the manifest (waits for background hashing on first use).
            TransferManifest manifest = manifestFuture.join();
            writeFrame(channel, MSG_MANIFEST, manifest.encode());

            // 3. Serve range and checksum requests until the client hangs up.
            while (true) {
                Frame request = readFrame(channel);
                activity.set(System.nanoTime());
                switch (request.type()) {
                    case MSG_RANGE_REQUEST -> serveRange(channel, request.payload(), activity);
                    case MSG_CHUNK_CHECKSUM_REQUEST -> serveChecksums(channel, request.payload());
                    default -> {
                        writeError(channel, ERR_BAD_REQUEST, "Unexpected frame type " + request.type());
                        return;
                    }
                }
            }
        } catch (IOException e) {
            // Normal end of session: client closed, or watchdog closed an idle channel.
        } catch (Exception e) {
            System.err.println("Transfer error for " + file.getFileName() + ": " + e);
            tryWriteError(channel, ERR_INTERNAL, "Internal error");
        } finally {
            lastActivityNanos.remove(channel);
        }
    }

    private void serveRange(SocketChannel channel, ByteBuffer payload, AtomicLong activity)
            throws IOException {
        long offset = payload.getLong();
        long length = payload.getLong();
        if (length == -1) {
            length = fileSize - offset;
        }
        if (offset < 0 || length < 0 || offset + length > fileSize) {
            writeError(channel, ERR_INVALID_RANGE,
                    "Requested [" + offset + ", +" + length + ") of " + fileSize + " bytes");
            throw new IOException("Client sent invalid range");
        }

        ByteBuffer start = ByteBuffer.allocate(16);
        start.putLong(offset).putLong(length).flip();
        writeFrame(channel, MSG_DATA_START, start);

        // Zero-copy send. Sliced so a stalled peer is detected by the watchdog
        // rather than blocking forever inside one giant transferTo call.
        long position = offset;
        long remaining = length;
        while (remaining > 0) {
            long sent = fileChannel.transferTo(position, Math.min(remaining, TRANSFER_SLICE_BYTES), channel);
            position += sent;
            remaining -= sent;
            activity.set(System.nanoTime());
        }

        ByteBuffer complete = ByteBuffer.allocate(8);
        complete.putLong(length).flip();
        writeFrame(channel, MSG_COMPLETE, complete);
    }

    private void serveChecksums(SocketChannel channel, ByteBuffer payload) throws IOException {
        long firstChunk = payload.getLong();
        int count = payload.getInt();
        int chunkSize = config.chunkSizeBytes();
        long chunkCount = (fileSize + chunkSize - 1) / chunkSize;
        int maxCount = (MAX_PAYLOAD_BYTES - 12) / 4;
        if (firstChunk < 0 || count <= 0 || count > maxCount || firstChunk + count > chunkCount) {
            writeError(channel, ERR_INVALID_RANGE, "Invalid checksum range");
            throw new IOException("Client sent invalid checksum range");
        }

        ByteBuffer response = ByteBuffer.allocate(12 + 4 * count);
        response.putLong(firstChunk).putInt(count);
        ByteBuffer readBuffer = ByteBuffer.allocateDirect(Math.min(chunkSize, Hashing.HASH_BUFFER_BYTES));
        CRC32C crc = new CRC32C();
        for (int i = 0; i < count; i++) {
            long chunkStart = (firstChunk + i) * (long) chunkSize;
            long chunkEnd = Math.min(chunkStart + chunkSize, fileSize);
            crc.reset();
            long position = chunkStart;
            while (position < chunkEnd) {
                readBuffer.clear().limit((int) Math.min(readBuffer.capacity(), chunkEnd - position));
                int read = fileChannel.read(readBuffer, position);
                if (read < 0) {
                    throw new IOException("File truncated while serving checksums");
                }
                readBuffer.flip();
                crc.update(readBuffer);
                position += read;
            }
            response.putInt((int) crc.getValue());
        }
        response.flip();
        writeFrame(channel, MSG_CHUNK_CHECKSUMS, response);
    }

    private static void tryWriteError(SocketChannel channel, short code, String message) {
        try {
            writeError(channel, code, message);
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        watchdog.shutdownNow();
        serverChannel.close();
        lastActivityNanos.keySet().forEach(FileSender::closeQuietly);
        connectionExecutor.shutdown();
        fileChannel.close();
    }
}
