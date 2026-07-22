package com.npdev.adapters.filestore.objectstore;

import com.npdev.kernel.ports.FileHandle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HARDEN-OBJSTORE-P3: proves the adapter against a real S3-compatible endpoint -- a mocked
 * {@link S3Client} (see {@link S3ObjectStoreFileStoreAdapterTest}) can't catch multipart,
 * streaming, credential, or endpoint-path-style bugs that only surface against a live service.
 */
class S3ObjectStoreFileStoreAdapterMinioLiveTest {

    private static final String BUCKET = "npdev-files";
    private static MinIOContainer MINIO;
    private static S3Client S3;
    private static S3ObjectStoreFileStoreAdapter ADAPTER;

    @BeforeAll
    static void startMinioAndAdapter() {
        MINIO = new MinIOContainer("minio/minio:RELEASE.2024-08-29T01-40-52Z");
        MINIO.start();

        S3 = S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .build();
        S3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());

        ADAPTER = new S3ObjectStoreFileStoreAdapter(S3, BUCKET);
    }

    @AfterAll
    static void stopMinio() {
        if (S3 != null) {
            S3.close();
        }
        if (MINIO != null) {
            MINIO.stop();
        }
    }

    @Test
    void putGetDeleteRoundTripsAgainstARealEndpoint() {
        byte[] bytes = "hello minio".getBytes(StandardCharsets.UTF_8);
        FileHandle handle = ADAPTER.put("tenant-a", "greeting.txt", "text/plain", bytes.length,
                new ByteArrayInputStream(bytes));

        assertTrue(handle.key().startsWith("tenant-a/"));
        assertTrue(ADAPTER.exists(handle));

        FileHandle resolved = ADAPTER.head(handle.storeId(), handle.key());
        assertEquals("text/plain", resolved.contentType());
        assertEquals("greeting.txt", resolved.originalName());
        assertEquals(bytes.length, resolved.sizeBytes());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ADAPTER.get(handle, out);
        assertArrayEquals(bytes, out.toByteArray());

        ADAPTER.delete(handle);
        assertFalse(ADAPTER.exists(handle));
        assertThrows(NoSuchElementException.class, () -> ADAPTER.get(handle, new ByteArrayOutputStream()));
    }

    @Test
    void largeFileStreamsThroughRealMultipartUploadWithoutOom() {
        int size = S3ObjectStoreFileStoreAdapter.PART_SIZE_BYTES + (6 * 1024 * 1024); // spans 2 parts, >5MB min part size
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i % 251);
        }

        FileHandle handle = ADAPTER.put("tenant-a", "big.bin", "application/octet-stream", size,
                new ByteArrayInputStream(bytes));
        assertEquals(size, handle.sizeBytes());

        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        ADAPTER.get(handle, out);
        assertArrayEquals(bytes, out.toByteArray());

        ADAPTER.delete(handle);
    }

    @Test
    void deletingAnUnknownKeyIsNotAnError() {
        FileHandle handle = new FileHandle(BUCKET, "tenant-a/does-not-exist", "text/plain", 0, "x.txt");
        assertThrows(NoSuchElementException.class, () -> ADAPTER.head(handle.storeId(), handle.key()));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> ADAPTER.delete(handle));
    }
}
