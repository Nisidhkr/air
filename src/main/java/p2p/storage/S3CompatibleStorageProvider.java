package p2p.storage;

import p2p.transfer.FileReceiver;
import p2p.util.Hashing;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The single S3-compatible {@link StorageProvider} implementation. AWS S3 and
 * MinIO speak the same API, so {@link S3StorageProvider} and
 * {@link MinioStorageProvider} are pure configuration over this class — one
 * wire implementation, zero duplicated logic (the platform's core rule).
 *
 * <p>Object metadata (display name, SHA-256, created-at) travels as S3 user
 * metadata, so {@link #stat} needs no side lookup. The authoritative index is
 * still the {@code files} table — listing a bucket is for sweeps only.
 *
 * <p>Uploads are spooled to a temp file first: that single pass computes the
 * SHA-256 and gives the SDK a known content length (no in-memory buffering,
 * no chunked-signing surprises on MinIO).
 */
public class S3CompatibleStorageProvider implements StorageProvider {

    private static final int COPY_BUFFER_BYTES = 256 * 1024;
    /** Objects at or above this size use multipart upload (backbone §9). */
    static final long MULTIPART_THRESHOLD = 64L * 1024 * 1024;  // 64 MB
    static final long PART_SIZE = 16L * 1024 * 1024;            // 16 MB parts

    private final S3Client s3;
    private final String bucket;
    private final Path spoolDir;

    public S3CompatibleStorageProvider(S3Client s3, String bucket, Path spoolDir)
            throws IOException {
        this.s3 = s3;
        this.bucket = bucket;
        this.spoolDir = spoolDir;
        Files.createDirectories(spoolDir);
    }

    @Override
    public StoredObject put(InputStream in, String fileName) throws IOException {
        String objectId = UUID.randomUUID().toString();
        Path spool = spoolDir.resolve(objectId + ".spool");
        long size = 0;
        try {
            try (OutputStream out = new BufferedOutputStream(
                    Files.newOutputStream(spool), COPY_BUFFER_BYTES)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    size += read;
                }
            }
            String sha256 = Hashing.toHex(Hashing.sha256Unchecked(spool));
            String safeName = FileReceiver.sanitizeFilename(fileName);
            long createdAt = System.currentTimeMillis();
            Map<String, String> metadata = Map.of(
                    "fylo-name", safeName,
                    "fylo-sha256", sha256,
                    "fylo-created", Long.toString(createdAt));
            long contentLength = size;
            try {
                if (usesMultipart(size)) {
                    // S3/MinIO cap a single PUT at 5 GiB; multipart is what
                    // makes the premium 100 GB+ file promise real.
                    multipartUpload(spool, objectId, metadata, size);
                } else {
                    s3.putObject(b -> b.bucket(bucket).key(objectId)
                                    .contentLength(contentLength)
                                    .metadata(metadata),
                            RequestBody.fromFile(spool));
                }
            } catch (S3Exception e) {
                throw new IOException("Object store rejected upload: " + e.getMessage(), e);
            }
            return new StoredObject(objectId, safeName, size, sha256, createdAt);
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    /** Threshold routing, exposed for tests: small objects go single-PUT. */
    static boolean usesMultipart(long sizeBytes) {
        return sizeBytes >= MULTIPART_THRESHOLD;
    }

    /**
     * Multipart upload from the spool file: fixed 16 MB parts read through
     * one reusable buffer (constant memory regardless of object size), parts
     * uploaded sequentially on the calling virtual thread, and the upload
     * aborted server-side on any failure so no orphaned parts accrue.
     */
    private void multipartUpload(Path spool, String objectId, Map<String, String> metadata,
                                 long size) throws IOException {
        CreateMultipartUploadResponse created = s3.createMultipartUpload(
                b -> b.bucket(bucket).key(objectId).metadata(metadata));
        String uploadId = created.uploadId();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(spool), COPY_BUFFER_BYTES)) {
            List<CompletedPart> parts = new ArrayList<>();
            byte[] buffer = new byte[(int) PART_SIZE];
            long remaining = size;
            int partNumber = 1;
            while (remaining > 0) {
                int read = in.readNBytes(buffer, 0, (int) Math.min(PART_SIZE, remaining));
                if (read <= 0) {
                    throw new IOException("Spool truncated during multipart upload");
                }
                final int pn = partNumber;
                final long partLength = read;
                UploadPartResponse part = s3.uploadPart(
                        b -> b.bucket(bucket).key(objectId).uploadId(uploadId)
                                .partNumber(pn).contentLength(partLength),
                        RequestBody.fromByteBuffer(ByteBuffer.wrap(buffer, 0, read)));
                parts.add(CompletedPart.builder().partNumber(pn).eTag(part.eTag()).build());
                remaining -= read;
                partNumber++;
            }
            s3.completeMultipartUpload(b -> b.bucket(bucket).key(objectId).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()));
        } catch (IOException | RuntimeException e) {
            try {
                s3.abortMultipartUpload(b -> b.bucket(bucket).key(objectId).uploadId(uploadId));
            } catch (S3Exception ignored) {
                // Best effort; lifecycle rules clean up aborted uploads.
            }
            throw e instanceof IOException io
                    ? io : new IOException("Multipart upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<StoredObject> stat(String objectId) throws IOException {
        try {
            HeadObjectResponse head = s3.headObject(b -> b.bucket(bucket).key(objectId));
            return Optional.of(fromMetadata(objectId, head.contentLength(), head.metadata()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            throw new IOException("Object store stat failed: " + e.getMessage(), e);
        }
    }

    /** Remote provider: no local channel; callers use {@link #open}. */
    @Override
    public Optional<FileChannel> openChannel(String objectId) {
        return Optional.empty();
    }

    @Override
    public InputStream open(String objectId, long offset) throws IOException {
        try {
            GetObjectRequest.Builder request = GetObjectRequest.builder()
                    .bucket(bucket).key(objectId);
            if (offset > 0) {
                request.range("bytes=" + offset + "-");
            }
            ResponseInputStream<?> stream = s3.getObject(request.build());
            return stream;
        } catch (NoSuchKeyException e) {
            throw new IOException("Object not found: " + objectId, e);
        } catch (S3Exception e) {
            throw new IOException("Object store read failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Path> localPath(String objectId) {
        return Optional.empty();
    }

    @Override
    public boolean delete(String objectId) throws IOException {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(objectId));
            return true;
        } catch (S3Exception e) {
            throw new IOException("Object store delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<StoredObject> list() throws IOException {
        try {
            List<StoredObject> objects = new ArrayList<>();
            s3.listObjectsV2Paginator(b -> b.bucket(bucket)).contents().forEach(summary -> {
                try {
                    stat(summary.key()).ifPresent(objects::add);
                } catch (IOException ignored) {
                    // Skip objects whose metadata is momentarily unreadable.
                }
            });
            return objects;
        } catch (S3Exception e) {
            throw new IOException("Object store list failed: " + e.getMessage(), e);
        }
    }

    private static StoredObject fromMetadata(String objectId, long size,
                                             Map<String, String> metadata) {
        return new StoredObject(objectId,
                metadata.getOrDefault("fylo-name", "file"),
                size,
                metadata.getOrDefault("fylo-sha256", ""),
                Long.parseLong(metadata.getOrDefault("fylo-created", "0")));
    }
}
