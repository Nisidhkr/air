package p2p.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Streaming hash helpers. Files are read through a single reusable buffer so
 * hashing a 40 GB file costs a constant ~1 MB of heap.
 */
public final class Hashing {

    /** Read size for hashing passes; large enough to keep the disk streaming sequentially. */
    public static final int HASH_BUFFER_BYTES = 1 << 20;

    private Hashing() {
    }

    public static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandated by the JCA spec", e);
        }
    }

    /** Computes the SHA-256 of an entire file without loading it into memory. */
    public static byte[] sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        ByteBuffer buffer = ByteBuffer.allocateDirect(HASH_BUFFER_BYTES);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (channel.read(buffer) != -1) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        return digest.digest();
    }

    public static String toHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    public static byte[] fromHex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    /** Unchecked adapter for use inside CompletableFuture pipelines. */
    public static byte[] sha256Unchecked(Path file) {
        try {
            return sha256(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
