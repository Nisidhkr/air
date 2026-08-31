package p2p.storage;

import p2p.transfer.FileReceiver;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Filesystem-backed {@link StorageProvider}. Objects live under
 * {@code <dataDir>/storage/<objectId>} with a sidecar {@code .meta} properties
 * file (name, size, sha256, createdAt). The object id embeds no user input, so
 * path traversal is impossible by construction.
 *
 * <p>This is the "one database is PostgreSQL, one object store is MinIO/S3"
 * seam: swap this class for an S3 implementation without touching callers.
 */
public final class LocalStorageProvider implements StorageProvider {

    private static final int COPY_BUFFER_BYTES = 256 * 1024;

    private final Path root;

    public LocalStorageProvider(Path dataDir) throws IOException {
        this.root = dataDir.resolve("storage");
        Files.createDirectories(root);
    }

    @Override
    public StoredObject put(InputStream in, String fileName) throws IOException {
        String objectId = UUID.randomUUID().toString();
        Path target = root.resolve(objectId);
        MessageDigest sha256 = newSha256();
        long size = 0;
        try (InputStream digesting = new DigestInputStream(new BufferedInputStream(in, COPY_BUFFER_BYTES), sha256);
             OutputStream out = new BufferedOutputStream(
                     Files.newOutputStream(target, StandardOpenOption.CREATE_NEW), COPY_BUFFER_BYTES)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int read;
            while ((read = digesting.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                size += read;
            }
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        StoredObject object = new StoredObject(objectId,
                FileReceiver.sanitizeFilename(fileName), size,
                HexFormat.of().formatHex(sha256.digest()), System.currentTimeMillis());
        writeMeta(object);
        return object;
    }

    @Override
    public Optional<StoredObject> stat(String objectId) throws IOException {
        Path meta = metaPath(objectId);
        if (!Files.isRegularFile(meta)) {
            return Optional.empty();
        }
        java.util.Properties props = new java.util.Properties();
        try (InputStream in = Files.newInputStream(meta)) {
            props.load(in);
        }
        return Optional.of(new StoredObject(objectId,
                props.getProperty("name", "file"),
                Long.parseLong(props.getProperty("size", "0")),
                props.getProperty("sha256", ""),
                Long.parseLong(props.getProperty("createdAt", "0"))));
    }

    @Override
    public Optional<FileChannel> openChannel(String objectId) throws IOException {
        Path path = objectPath(objectId);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(FileChannel.open(path, StandardOpenOption.READ));
    }

    @Override
    public InputStream open(String objectId, long offset) throws IOException {
        InputStream in = Files.newInputStream(objectPath(objectId));
        try {
            in.skipNBytes(offset);
        } catch (IOException e) {
            in.close();
            throw e;
        }
        return in;
    }

    @Override
    public Optional<Path> localPath(String objectId) {
        Path path = objectPath(objectId);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    @Override
    public boolean delete(String objectId) throws IOException {
        boolean removed = Files.deleteIfExists(objectPath(objectId));
        Files.deleteIfExists(metaPath(objectId));
        return removed;
    }

    @Override
    public List<StoredObject> list() throws IOException {
        List<StoredObject> objects = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*.meta")) {
            for (Path meta : stream) {
                String name = meta.getFileName().toString();
                stat(name.substring(0, name.length() - ".meta".length())).ifPresent(objects::add);
            }
        }
        return objects;
    }

    private Path objectPath(String objectId) {
        requireOpaqueId(objectId);
        return root.resolve(objectId);
    }

    private Path metaPath(String objectId) {
        requireOpaqueId(objectId);
        return root.resolve(objectId + ".meta");
    }

    /** Object ids are always our own UUIDs; reject anything path-like. */
    private static void requireOpaqueId(String objectId) {
        if (objectId == null || objectId.contains("/") || objectId.contains("\\")
                || objectId.contains("..")) {
            throw new IllegalArgumentException("Invalid object id");
        }
    }

    private void writeMeta(StoredObject object) throws IOException {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("name", object.fileName());
        props.setProperty("size", Long.toString(object.size()));
        props.setProperty("sha256", object.sha256Hex());
        props.setProperty("createdAt", Long.toString(object.createdAtEpochMs()));
        try (OutputStream out = Files.newOutputStream(metaPath(object.objectId()))) {
            props.store(out, "Fylo stored object");
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is mandatory in every JRE", e);
        }
    }
}
