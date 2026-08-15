package com.finalexec.config;

import com.npdev.adapters.filestore.inproc.FileSystemFileStoreAdapter;
import com.npdev.adapters.filestore.objectstore.S3ObjectStoreFileStoreAdapter;
import com.npdev.kernel.ports.FileStoreContract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * LIFT-UPLOAD-P1/P3 + HARDEN-OBJSTORE-P2: wires the {@link FileStoreContract} adapter by config --
 * {@code npdev.filestore.provider: inproc | objectstore}, defaulting to {@code inproc} so an
 * unconfigured app keeps today's dev/filesystem behavior unchanged. Setting {@code objectstore}
 * switches the same binary to the S3-compatible prod adapter (AWS S3, MinIO, R2, ...); credentials
 * come from config/env, never committed.
 */
@Configuration
public class NpdevFileStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "npdev.filestore.provider", havingValue = "inproc", matchIfMissing = true)
    public FileStoreContract inprocFileStoreContract(
            // LNCH-7: '/' (not '\\') in the default -- Java's NIO Path accepts '/' on Windows too,
            // but a literal backslash inside a *property value string* is not a path separator to
            // Linux at all, just an ordinary character; the whole "${user.dir}\npdev-files" string
            // became one bogus single-segment directory name on the container's Linux filesystem
            // (confirmed live: docker compose up on Alpine failed with
            // "AccessDeniedException: /app\npdev-files" when trying to create it).
            @Value("${npdev.filestore.root:${user.dir}/npdev-files}") String root
    ) {
        return new FileSystemFileStoreAdapter(Path.of(root));
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.filestore.provider", havingValue = "objectstore")
    public FileStoreContract objectStoreFileStoreContract(
            @Value("${npdev.filestore.objectstore.bucket:}") String bucket,
            @Value("${npdev.filestore.objectstore.region:us-east-1}") String region,
            @Value("${npdev.filestore.objectstore.endpoint:}") String endpoint,
            @Value("${npdev.filestore.objectstore.pathStyleAccess:true}") boolean pathStyleAccess,
            @Value("${npdev.filestore.objectstore.accessKeyId:}") String accessKeyId,
            @Value("${npdev.filestore.objectstore.secretAccessKey:}") String secretAccessKey,
            // REG-166: same class of gap RUN-4 already fixed for the two CapabilityAdapter network
            // adapters (external-ai-http, mail-smtp) -- neither timeout nor retry was ever
            // configured here, so an object-store backend that accepts a TCP connection and then
            // stalls mid-response (a real MinIO/R2 backpressure failure mode) blocked the calling
            // thread forever. apiCallTimeout bounds the WHOLE operation including retries;
            // apiCallAttemptTimeout bounds each individual attempt -- both explicit rather than
            // whatever the SDK's own unstated default resolves to.
            @Value("${npdev.filestore.objectstore.apiCallTimeoutMs:60000}") long apiCallTimeoutMs,
            @Value("${npdev.filestore.objectstore.apiCallAttemptTimeoutMs:20000}") long apiCallAttemptTimeoutMs,
            @Value("${npdev.filestore.objectstore.maxRetries:2}") int maxRetries
    ) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "npdev.filestore.provider=objectstore requires npdev.filestore.objectstore.bucket to be set");
        }
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyleAccess).build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMillis(apiCallTimeoutMs))
                        .apiCallAttemptTimeout(Duration.ofMillis(apiCallAttemptTimeoutMs))
                        .retryPolicy(RetryPolicy.builder(RetryMode.STANDARD).numRetries(maxRetries).build())
                        .build());
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (accessKeyId != null && !accessKeyId.isBlank() && secretAccessKey != null && !secretAccessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return new S3ObjectStoreFileStoreAdapter(builder.build(), bucket);
    }
}
