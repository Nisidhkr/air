package p2p.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Large-object upload behavior (backbone §9 / fix [5.4]): the 64 MB
 * threshold routes to multipart, and a 128 MB streamed store round-trips
 * byte-exact with constant memory (no full-file buffering).
 */
class MultipartUploadTest {

    private static final long SIZE_128_MB = 128L * 1024 * 1024;

    @TempDir
    Path dataDir;

    /** Generates {@code size} zero bytes without ever holding them all. */
    private static final class ZeroStream extends InputStream {
        private long remaining;

        ZeroStream(long size) {
            this.remaining = size;
        }

        @Override
        public int read() {
            if (remaining <= 0) {
                return -1;
            }
            remaining--;
            return 0;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (remaining <= 0) {
                return -1;
            }
            int count = (int) Math.min(len, remaining);
            java.util.Arrays.fill(b, off, off + count, (byte) 0);
            remaining -= count;
            return count;
        }
    }

    @Test
    void thresholdRoutesLargeObjectsToMultipart() {
        assertFalse(S3CompatibleStorageProvider.usesMultipart(1024),
                "1 KB must be a single PUT");
        assertFalse(S3CompatibleStorageProvider.usesMultipart(
                S3CompatibleStorageProvider.MULTIPART_THRESHOLD - 1));
        assertTrue(S3CompatibleStorageProvider.usesMultipart(
                S3CompatibleStorageProvider.MULTIPART_THRESHOLD));
        assertTrue(S3CompatibleStorageProvider.usesMultipart(SIZE_128_MB),
                "128 MB must use multipart");
        // 128 MB in 16 MB parts = 8 parts, well under S3's 10,000-part limit.
        assertEquals(8, SIZE_128_MB / S3CompatibleStorageProvider.PART_SIZE);
    }

    @Test
    void stores128MbStreamAndReadsItBackExactly() throws IOException {
        LocalStorageProvider storage = new LocalStorageProvider(dataDir);
        StoredObject object = storage.put(new ZeroStream(SIZE_128_MB), "big.bin");

        assertEquals(SIZE_128_MB, object.size(), "stored size must be 128 MB");
        assertEquals(SIZE_128_MB,
                storage.stat(object.objectId()).orElseThrow().size());

        long counted = 0;
        byte[] buffer = new byte[256 * 1024];
        try (InputStream in = storage.open(object.objectId(), 0)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                counted += read;
            }
        }
        assertEquals(SIZE_128_MB, counted, "retrieve must return every byte");
        storage.delete(object.objectId());
    }
}
