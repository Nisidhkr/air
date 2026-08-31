package p2p.storage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/**
 * MinIO configuration of the S3-compatible provider: custom endpoint +
 * path-style addressing (MinIO does not use virtual-host buckets by
 * default). Everything else — uploads, ranged reads, metadata, deletes — is
 * the shared implementation.
 */
public final class MinioStorageProvider extends S3CompatibleStorageProvider {

    public MinioStorageProvider(String endpoint, String bucket, String accessKey,
                                String secretKey, Path spoolDir) throws IOException {
        super(client(endpoint, accessKey, secretKey), bucket, spoolDir);
    }

    private static S3Client client(String endpoint, String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1) // required by the SDK; MinIO ignores it
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
