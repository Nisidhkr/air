package p2p.transfer;

import p2p.protocol.TransferManifest;
import p2p.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Durable record of how much of each segment has been received, persisted next
 * to the partial file as {@code <name>.resume}. On reconnect the receiver
 * resumes each segment from {@code start + received} — already-downloaded
 * bytes are never re-fetched.
 *
 * <p>Saves are atomic (write temp file, then {@code ATOMIC_MOVE}) so a crash
 * mid-save can never corrupt resume metadata. The state may be slightly stale
 * after a crash (bytes written but not yet recorded); resuming then re-fetches
 * at most a few MB and overwrites identical data, which is harmless.
 */
public final class ResumeState {

    /** One contiguous region [start, end) downloaded by one connection. */
    public static final class Segment {
        private final long start;
        private final long end;
        private volatile long received;

        Segment(long start, long end, long received) {
            this.start = start;
            this.end = end;
            this.received = received;
        }

        public long start() {
            return start;
        }

        public long end() {
            return end;
        }

        public long received() {
            return received;
        }

        public long remaining() {
            return (end - start) - received;
        }

        public void addReceived(long bytes) {
            received += bytes; // single writer per segment; volatile for cross-thread reads
        }
    }

    private final UUID transferId;
    private final long fileSize;
    private final String sha256Hex; // empty string when the manifest carried no hash
    private final int chunkSize;
    private final List<Segment> segments;

    private ResumeState(UUID transferId, long fileSize, String sha256Hex, int chunkSize,
                        List<Segment> segments) {
        this.transferId = transferId;
        this.fileSize = fileSize;
        this.sha256Hex = sha256Hex;
        this.chunkSize = chunkSize;
        this.segments = segments;
    }

    /** Splits the file into {@code segmentCount} near-equal contiguous segments. */
    public static ResumeState forManifest(TransferManifest manifest, int segmentCount) {
        long size = manifest.fileSize();
        int count = size == 0 ? 1 : (int) Math.min(segmentCount, Math.max(1, size / (8L << 20) + 1));
        List<Segment> segments = new ArrayList<>(count);
        long base = size / count;
        long position = 0;
        for (int i = 0; i < count; i++) {
            long end = (i == count - 1) ? size : position + base;
            segments.add(new Segment(position, end, 0));
            position = end;
        }
        String hex = manifest.hasHash() ? Hashing.toHex(manifest.sha256()) : "";
        return new ResumeState(manifest.transferId(), size, hex, manifest.chunkSize(), segments);
    }

    public static ResumeState load(Path metaFile) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(metaFile)) {
            props.load(in);
        }
        UUID transferId = UUID.fromString(require(props, "transferId"));
        long fileSize = Long.parseLong(require(props, "fileSize"));
        String sha256Hex = props.getProperty("sha256", "");
        int chunkSize = Integer.parseInt(require(props, "chunkSize"));
        int segmentCount = Integer.parseInt(require(props, "segmentCount"));
        List<Segment> segments = new ArrayList<>(segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            String[] parts = require(props, "segment." + i).split(":");
            segments.add(new Segment(
                    Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2])));
        }
        return new ResumeState(transferId, fileSize, sha256Hex, chunkSize, segments);
    }

    private static String require(Properties props, String key) throws IOException {
        String value = props.getProperty(key);
        if (value == null) {
            throw new IOException("Corrupt resume file: missing " + key);
        }
        return value;
    }

    /**
     * A resume file is only valid if it describes the same logical transfer.
     * If the source file changed (different id, size, or hash) the partial
     * data must be discarded.
     */
    public boolean matches(TransferManifest manifest) {
        if (fileSize != manifest.fileSize() || chunkSize != manifest.chunkSize()) {
            return false;
        }
        if (!transferId.equals(manifest.transferId())) {
            return false;
        }
        if (manifest.hasHash() && !sha256Hex.isEmpty()) {
            return sha256Hex.equals(Hashing.toHex(manifest.sha256()));
        }
        return true;
    }

    public synchronized void save(Path metaFile) throws IOException {
        Properties props = new Properties();
        props.setProperty("transferId", transferId.toString());
        props.setProperty("fileSize", Long.toString(fileSize));
        props.setProperty("sha256", sha256Hex);
        props.setProperty("chunkSize", Integer.toString(chunkSize));
        props.setProperty("segmentCount", Integer.toString(segments.size()));
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            props.setProperty("segment." + i, s.start() + ":" + s.end() + ":" + s.received());
        }
        StringWriter writer = new StringWriter();
        props.store(writer, "PeerLink resume state");
        Path temp = metaFile.resolveSibling(metaFile.getFileName() + ".tmp");
        Files.writeString(temp, writer.toString());
        Files.move(temp, metaFile, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    public List<Segment> segments() {
        return segments;
    }

    public long totalReceived() {
        long sum = 0;
        for (Segment s : segments) {
            sum += s.received();
        }
        return sum;
    }

    public boolean isComplete() {
        return totalReceived() == fileSize;
    }

    public long fileSize() {
        return fileSize;
    }
}
