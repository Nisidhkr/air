package p2p.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Provider selection — the ONE place deployment chooses where bytes live.
 *
 * <pre>
 * FYLO_STORAGE=local   (default)  → LocalStorageProvider under dataDir
 * FYLO_STORAGE=minio               → MinioStorageProvider
 *     S3_ENDPOINT, S3_BUCKET, S3_ACCESS_KEY, S3_SECRET_KEY
 * FYLO_STORAGE=s3                  → S3StorageProvider
 *     S3_REGION, S3_BUCKET [, S3_ACCESS_KEY, S3_SECRET_KEY]
 * </pre>
 *
 * Migration between providers is data-only: copy objects, keep ids (they are
 * the object keys), flip the env var. No code changes anywhere else.
 */
public final class StorageProviders {

    private StorageProviders() {
    }

    public static StorageProvider fromEnv(Path dataDir) throws IOException {
        return create(dataDir, System.getenv());
    }

    /** Testable factory: same selection logic, explicit environment map. */
    public static StorageProvider create(Path dataDir, java.util.Map<String, String> env)
            throws IOException {
        String kind = env.getOrDefault("FYLO_STORAGE", "local")
                .toLowerCase(java.util.Locale.ROOT);
        Path spool = dataDir.resolve("spool");
        return switch (kind) {
            case "local" -> new LocalStorageProvider(dataDir);
            case "minio" -> new MinioStorageProvider(
                    required(env, "S3_ENDPOINT"), required(env, "S3_BUCKET"),
                    required(env, "S3_ACCESS_KEY"), required(env, "S3_SECRET_KEY"), spool);
            case "s3" -> new S3StorageProvider(
                    required(env, "S3_REGION"), required(env, "S3_BUCKET"),
                    env.get("S3_ACCESS_KEY"), env.get("S3_SECRET_KEY"), spool);
            default -> throw new IOException("Unknown FYLO_STORAGE: " + kind);
        };
    }

    private static String required(java.util.Map<String, String> env, String name)
            throws IOException {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IOException(name + " must be set for this storage provider");
        }
        return value;
    }
}
