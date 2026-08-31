package p2p.infra;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import p2p.storage.MinioStorageProvider;
import p2p.storage.StorageProvider;
import p2p.storage.StorageProviders;
import p2p.storage.StoredObject;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backbone §18.1: the ONE S3-compatible provider against REAL MinIO,
 * including the >64 MB multipart path that backs the premium 100 GB+ file
 * promise. Requires Docker (skip with -DexcludedGroups=integration).
 */
@Tag("integration")
@Testcontainers
class MinioIntegrationTest {

    private static final long SIZE_128_MB = 128L * 1024 * 1024;

    @Container
    static final MinIOContainer MINIO =
            new MinIOContainer("minio/minio:RELEASE.2023-09-04T19-57-37Z");

    @TempDir
    static Path spoolDir;

    static MinioStorageProvider storage;

    @BeforeAll
    static void createBucketAndProvider() throws IOException {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build()) {
            s3.createBucket(b -> b.bucket("fylo-test"));
        }
        storage = new MinioStorageProvider(MINIO.getS3URL(), "fylo-test",
                MINIO.getUserName(), MINIO.getPassword(), spoolDir);
    }

    /** Streams zeros without materializing them. */
    private static final class ZeroStream extends InputStream {
        private long remaining;

        ZeroStream(long size) {
            this.remaining = size;
        }

        @Override
        public int read() {
            return remaining-- > 0 ? 0 : -1;
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

    private static long countBytes(String objectId) throws IOException {
        long counted = 0;
        byte[] buffer = new byte[256 * 1024];
        try (InputStream in = storage.open(objectId, 0)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                counted += read;
            }
        }
        return counted;
    }

    @Test
    void storeAndRetrieve_smallFile() throws IOException {
        StoredObject object = storage.put(
                new ByteArrayInputStream(new byte[1024]), "small.bin");
        assertEquals(1024, object.size());
        assertEquals(1024, countBytes(object.objectId()));
        assertEquals("small.bin", storage.stat(object.objectId()).orElseThrow().fileName());
    }

    @Test
    void storeAndRetrieve_largeFile_multipart() throws IOException {
        // 128 MB ≥ the 64 MB threshold → multipart route (8 × 16 MB parts).
        StoredObject object = storage.put(new ZeroStream(SIZE_128_MB), "big.bin");
        assertEquals(SIZE_128_MB, object.size());
        assertEquals(SIZE_128_MB, countBytes(object.objectId()));
        // Ranged GET still works on the multipart-assembled object.
        try (InputStream in = storage.open(object.objectId(), SIZE_128_MB - 100)) {
            assertEquals(100, in.readAllBytes().length);
        }
        storage.delete(object.objectId());
    }

    @Test
    void delete_removesObject() throws IOException {
        StoredObject object = storage.put(
                new ByteArrayInputStream("bye".getBytes()), "gone.txt");
        assertTrue(storage.stat(object.objectId()).isPresent());
        storage.delete(object.objectId());
        assertFalse(storage.stat(object.objectId()).isPresent());
    }

    @Test
    void storageProvider_factory_selectsMinio() throws IOException {
        StorageProvider provider = StorageProviders.create(
                Files.createTempDirectory("fylo-factory"),
                Map.of("FYLO_STORAGE", "minio",
                        "S3_ENDPOINT", MINIO.getS3URL(),
                        "S3_BUCKET", "fylo-test",
                        "S3_ACCESS_KEY", MINIO.getUserName(),
                        "S3_SECRET_KEY", MINIO.getPassword()));
        assertInstanceOf(MinioStorageProvider.class, provider);
    }
}
