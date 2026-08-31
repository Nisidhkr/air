package p2p.storage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.nio.file.Path;

/**
 * AWS S3 configuration of the S3-compatible provider. Credentials come from
 * the standard AWS chain (env, profile, instance role) unless explicit keys
 * are given.
 */
public final class S3StorageProvider extends S3CompatibleStorageProvider {

    public S3StorageProvider(String region, String bucket, String accessKey, String secretKey,
                             Path spoolDir) throws IOException {
        super(client(region, accessKey, secretKey), bucket, spoolDir);
    }

    private static S3Client client(String region, String accessKey, String secretKey) {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder());
        if (accessKey != null && !accessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }
}
