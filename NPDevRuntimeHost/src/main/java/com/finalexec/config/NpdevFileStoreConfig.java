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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.nio.file.Path;

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
            @Value("${npdev.filestore.root:${user.dir}\\npdev-files}") String root
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
            @Value("${npdev.filestore.objectstore.secretAccessKey:}") String secretAccessKey
    ) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "npdev.filestore.provider=objectstore requires npdev.filestore.objectstore.bucket to be set");
        }
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyleAccess).build());
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
