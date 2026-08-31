package p2p.transfer;

import p2p.protocol.TransferException;
import p2p.protocol.TransferManifest;
import p2p.util.Hashing;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

/**
 * Downloads a file from a {@link FileSender} with parallel segments, exact
 * byte-offset resume, automatic retries, and end-to-end integrity checking.
 *
 * <p><b>Resume:</b> data lands in {@code <name>.part}; per-segment progress is
 * persisted in {@code <name>.resume} (see {@link ResumeState}). Reconnecting
 * resumes each segment at {@code start + received} — bytes already on disk are
 * never transferred again.
 *
 * <p><b>Integrity:</b> after completion the file is read back once, computing
 * the whole-file SHA-256 and per-chunk CRC32C in the same pass. On SHA-256
 * mismatch the chunk CRCs are compared against the sender's to locate and
 * re-fetch only the corrupted chunks (a few MB) instead of failing a 40 GB
 * transfer; a file that still fails verification is rejected and deleted.
 *
 * <p><b>Memory:</b> each segment thread owns one {@value #RECEIVE_BUFFER_BYTES}-byte
 * direct buffer; with 8 connections that is 8 MB, independent of file size.
 */
public final class FileReceiver {

    private static final int RECEIVE_BUFFER_BYTES = 1 << 20;
    /** Persist resume metadata roughly every 32 MB per segment. */
    private static final long SAVE_INTERVAL_BYTES = 32L << 20;

    private final InetSocketAddress address;
    private final String token;
    private final Path targetDirectory;
    private final TransferConfig config;

    private final java.util.Set<PeerClient> activeClients =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean aborted;

    public FileReceiver(String host, int port, String token, Path targetDirectory, TransferConfig config) {
        this.address = new InetSocketAddress(host, port);
        this.token = token;
        this.targetDirectory = targetDirectory;
        this.config = config;
    }

    /**
     * Aborts an in-flight {@link #download} from another thread by closing all
     * open connections; blocked reads fail immediately and no retries are
     * attempted. Resume metadata stays on disk, so a later download() resumes
     * from the exact byte offset — this is how pause is implemented.
     */
    public void abort() {
        aborted = true;
        for (PeerClient client : activeClients) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Opens a connection that {@link #abort()} can reach. */
    private PeerClient openClient() throws IOException {
        if (aborted) {
            throw new IOException("Transfer aborted");
        }
        PeerClient client = PeerClient.connect(address, token, config);
        activeClients.add(client);
        if (aborted) { // abort() may have raced past the add
            activeClients.remove(client);
            client.close();
            throw new IOException("Transfer aborted");
        }
        return client;
    }

    private void closeClient(PeerClient client) {
        activeClients.remove(client);
        try {
            client.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Runs the download to completion (or throws). Returns the final file path.
     *
     * @param progressListener called about once per second from a reporter
     *                         thread; may be {@code null}.
     */
    public Path download(Consumer<ProgressTracker.Snapshot> progressListener) throws IOException {
        TransferManifest manifest;
        PeerClient probe = openClient();
        try {
            manifest = probe.manifest();
        } finally {
            closeClient(probe);
        }

        String safeName = sanitizeFilename(manifest.filename());
        Files.createDirectories(targetDirectory);
        Path partFile = targetDirectory.resolve(safeName + ".part");
        Path resumeFile = targetDirectory.resolve(safeName + ".resume");

        ResumeState state = loadOrCreateState(manifest, partFile, resumeFile);
        ProgressTracker progress = new ProgressTracker(manifest.fileSize(), state.totalReceived());

        try (FileChannel partChannel = FileChannel.open(partFile,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            // Preallocate so positioned writes from parallel segments cannot
            // race file growth, and so "disk full" fails fast, before hours of
            // transfer. On most filesystems this creates a sparse file.
            if (partChannel.size() != manifest.fileSize()) {
                ensureDiskSpace(partFile, manifest.fileSize());
                partChannel.truncate(0);
                if (manifest.fileSize() > 0) {
                    partChannel.write(ByteBuffer.allocate(1), manifest.fileSize() - 1);
                }
            }

            downloadSegments(manifest, state, partChannel, resumeFile, progress, progressListener);
            partChannel.force(true);

            verifyAndRepair(manifest, partChannel, progress);
        }

        Path target = uniqueTarget(targetDirectory.resolve(safeName));
        Files.move(partFile, target, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(resumeFile);
        return target;
    }

    private ResumeState loadOrCreateState(TransferManifest manifest, Path partFile, Path resumeFile)
            throws IOException {
        if (Files.exists(resumeFile) && Files.exists(partFile)) {
            try {
                ResumeState existing = ResumeState.load(resumeFile);
                // The part file must already be at full preallocated size;
                // otherwise its contents cannot be trusted against the state.
                if (existing.matches(manifest) && Files.size(partFile) == manifest.fileSize()) {
                    return existing;
                }
                System.err.println("Source file changed since partial download; restarting from zero");
            } catch (IOException | RuntimeException e) {
                System.err.println("Unreadable resume file, restarting: " + e.getMessage());
            }
        }
        Files.deleteIfExists(partFile);
        Files.deleteIfExists(resumeFile);
        ResumeState fresh = ResumeState.forManifest(manifest, config.parallelConnections());
        fresh.save(resumeFile);
        return fresh;
    }

    private void downloadSegments(TransferManifest manifest, ResumeState state, FileChannel partChannel,
                                  Path resumeFile, ProgressTracker progress,
                                  Consumer<ProgressTracker.Snapshot> progressListener) throws IOException {
        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "transfer-progress");
            t.setDaemon(true);
            return t;
        });
        if (progressListener != null) {
            reporter.scheduleAtFixedRate(() -> progressListener.accept(progress.snapshot()),
                    1, 1, TimeUnit.SECONDS);
        }

        try (ExecutorService segmentExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            for (ResumeState.Segment segment : state.segments()) {
                if (segment.remaining() > 0) {
                    tasks.add(CompletableFuture.runAsync(
                            () -> downloadSegmentWithRetries(manifest, state, segment, partChannel,
                                    resumeFile, progress),
                            segmentExecutor));
                }
            }
            try {
                CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
            } catch (CompletionException e) {
                throw unwrap(e);
            }
        } finally {
            reporter.shutdownNow();
            state.save(resumeFile);
            if (progressListener != null) {
                progressListener.accept(progress.snapshot());
            }
        }
    }

    private void downloadSegmentWithRetries(TransferManifest manifest, ResumeState state,
                                            ResumeState.Segment segment, FileChannel partChannel,
                                            Path resumeFile, ProgressTracker progress) {
        int attempt = 0;
        while (true) {
            try {
                downloadSegment(manifest, state, segment, partChannel, resumeFile, progress);
                return;
            } catch (TransferException e) {
                if (!e.isRetryable()) {
                    throw new CompletionException(e); // auth failure, range rejected, file gone
                }
                attempt = backoffOrFail(attempt, e);
            } catch (IOException e) {
                // Connection reset, timeout-by-watchdog, premature EOF: the
                // resume state already reflects every byte written, so the
                // next attempt continues at the exact offset.
                attempt = backoffOrFail(attempt, e);
            }
        }
    }

    private int backoffOrFail(int attempt, IOException cause) {
        if (aborted) {
            throw new CompletionException(new IOException("Transfer aborted", cause));
        }
        attempt++;
        if (attempt > config.maxRetries()) {
            throw new CompletionException(new IOException(
                    "Transfer failed after " + config.maxRetries() + " retries", cause));
        }
        long sleepMillis = Math.min(30_000L, 1000L << (attempt - 1));
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CompletionException(new IOException("Transfer interrupted", ie));
        }
        return attempt;
    }

    private void downloadSegment(TransferManifest manifest, ResumeState state,
                                 ResumeState.Segment segment, FileChannel partChannel,
                                 Path resumeFile, ProgressTracker progress) throws IOException {
        PeerClient client = openClient();
        try {
            if (!client.manifest().transferId().equals(manifest.transferId())) {
                throw new TransferException(p2p.protocol.PeerLinkProtocol.ERR_NOT_FOUND,
                        "Offered file changed during transfer");
            }
            long position = segment.start() + segment.received();
            long remaining = segment.remaining();
            client.requestRange(position, remaining);

            ByteBuffer buffer = ByteBuffer.allocateDirect(RECEIVE_BUFFER_BYTES);
            long unsavedBytes = 0;
            while (remaining > 0) {
                buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
                int read = client.channel().read(buffer);
                if (read < 0) {
                    throw new IOException("Connection closed with " + remaining + " bytes outstanding");
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    partChannel.write(buffer, position + buffer.position());
                }
                position += read;
                remaining -= read;
                segment.addReceived(read);
                progress.add(read);
                unsavedBytes += read;
                if (unsavedBytes >= SAVE_INTERVAL_BYTES) {
                    state.save(resumeFile);
                    unsavedBytes = 0;
                }
            }
            client.readComplete();
            state.save(resumeFile);
        } finally {
            closeClient(client);
        }
    }

    private void verifyAndRepair(TransferManifest manifest, FileChannel partChannel,
                                 ProgressTracker progress) throws IOException {
        if (!manifest.hasHash()) {
            return;
        }
        int[] localCrcs = new int[(int) manifest.chunkCount()];
        if (MessageDigest.isEqual(hashAndCrc(partChannel, manifest, localCrcs), manifest.sha256())) {
            return;
        }

        // Whole-file hash failed: locate corrupted chunks via CRC comparison
        // and re-fetch just those.
        List<Long> badChunks = findCorruptChunks(manifest, localCrcs);
        if (badChunks.isEmpty()) {
            // CRCs all match but SHA-256 does not: cannot localize, reject.
            throw new IOException("SHA-256 verification failed and corruption could not be localized");
        }
        System.err.println("Verification failed; re-fetching " + badChunks.size() + " corrupted chunk(s)");
        PeerClient client = openClient();
        try {
            ByteBuffer buffer = ByteBuffer.allocateDirect(RECEIVE_BUFFER_BYTES);
            for (long chunk : badChunks) {
                long start = chunk * (long) manifest.chunkSize();
                long length = Math.min(manifest.chunkSize(), manifest.fileSize() - start);
                client.requestRange(start, length);
                long position = start;
                long remaining = length;
                while (remaining > 0) {
                    buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
                    int read = client.channel().read(buffer);
                    if (read < 0) {
                        throw new IOException("Connection closed during chunk repair");
                    }
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        partChannel.write(buffer, position + buffer.position());
                    }
                    position += read;
                    remaining -= read;
                }
                client.readComplete();
            }
        } finally {
            closeClient(client);
        }
        partChannel.force(true);

        if (!MessageDigest.isEqual(hashAndCrc(partChannel, manifest, localCrcs), manifest.sha256())) {
            throw new IOException("SHA-256 verification failed after chunk repair; file rejected");
        }
    }

    /** One sequential pass computing whole-file SHA-256 and per-chunk CRC32C together. */
    private byte[] hashAndCrc(FileChannel channel, TransferManifest manifest, int[] crcsOut)
            throws IOException {
        MessageDigest sha = Hashing.sha256Digest();
        CRC32C crc = new CRC32C();
        ByteBuffer buffer = ByteBuffer.allocateDirect(Hashing.HASH_BUFFER_BYTES);
        long position = 0;
        int chunkIndex = 0;
        long chunkEnd = Math.min(manifest.chunkSize(), manifest.fileSize());
        while (position < manifest.fileSize()) {
            buffer.clear().limit((int) Math.min(buffer.capacity(), chunkEnd - position));
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new IOException("Partial file truncated during verification");
            }
            buffer.flip();
            sha.update(buffer.duplicate());
            crc.update(buffer);
            position += read;
            if (position == chunkEnd) {
                crcsOut[chunkIndex++] = (int) crc.getValue();
                crc.reset();
                chunkEnd = Math.min(chunkEnd + manifest.chunkSize(), manifest.fileSize());
            }
        }
        return sha.digest();
    }

    private List<Long> findCorruptChunks(TransferManifest manifest, int[] localCrcs) throws IOException {
        List<Long> bad = new ArrayList<>();
        int batchLimit = 100_000;
        PeerClient client = openClient();
        try {
            long chunkCount = manifest.chunkCount();
            for (long first = 0; first < chunkCount; first += batchLimit) {
                int count = (int) Math.min(batchLimit, chunkCount - first);
                int[] remote = client.fetchChunkChecksums(first, count);
                for (int i = 0; i < count; i++) {
                    if (remote[i] != localCrcs[(int) first + i]) {
                        bad.add(first + i);
                    }
                }
            }
        } finally {
            closeClient(client);
        }
        return bad;
    }

    private static void ensureDiskSpace(Path file, long requiredBytes) throws IOException {
        long usable = Files.getFileStore(file.getParent()).getUsableSpace();
        if (usable < requiredBytes) {
            throw new IOException("Insufficient disk space: need " + requiredBytes
                    + " bytes, " + usable + " available");
        }
    }

    /** Strips any directory components so a malicious filename cannot escape the target dir. */
    public static String sanitizeFilename(String filename) {
        String name = Path.of(filename.replace('\\', '/')).getFileName().toString();
        name = name.replaceAll("[\\x00-\\x1f]", "_");
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            name = "unnamed-file";
        }
        return name;
    }

    private static Path uniqueTarget(Path desired) {
        if (!Files.exists(desired)) {
            return desired;
        }
        String name = desired.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            Path candidate = desired.resolveSibling(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    private static IOException unwrap(CompletionException e) {
        if (e.getCause() instanceof IOException io) {
            return io;
        }
        return new IOException(e.getCause() == null ? e : e.getCause());
    }
}
