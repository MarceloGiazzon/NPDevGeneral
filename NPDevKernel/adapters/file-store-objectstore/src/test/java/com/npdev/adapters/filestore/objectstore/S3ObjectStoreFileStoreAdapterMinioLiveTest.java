package com.npdev.adapters.filestore.objectstore;

import com.npdev.kernel.ports.FileHandle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
    private static LocalStackContainer LOCALSTACK;
    private static S3Client S3;
    private static S3ObjectStoreFileStoreAdapter ADAPTER;

    @BeforeAll
    static void startLocalstackAndAdapter() {
        // RUN-31 item 2: same skip-cleanly convention PostgresTestSupport already uses --
        // scripts/policy/local-test-profile.json deliberately keeps Docker off for local/agent work
        // (NPDev_General/CLAUDE.md, "Local machine resource policy"), so this must abort the class
        // cleanly rather than let the container throw and report as an opaque initializationError.
        Assumptions.assumeTrue(minioEnabled(),
                "S3-compatible engine disabled locally (scripts/policy/local-test-profile.json) -- "
                        + "set NPDEV_TEST_PROFILE_ENGINES=minio to opt in, or run with CI=true");
        // Was MinIO; switched 2026-09-29 after MinIO deleted minio/minio from Docker Hub
        // (2026-09-11) and its quay.io mirror stopped allowing anonymous pulls shortly after
        // (confirmed live in CI: consistent ConditionTimeoutException on every quay.io pull
        // attempt). LocalStack's S3 service is a drop-in test double, still actively published.
        LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
                .withServices(LocalStackContainer.Service.S3);
        LOCALSTACK.start();

        S3 = S3Client.builder()
                .region(Region.of(LOCALSTACK.getRegion()))
                .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
        S3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());

        ADAPTER = new S3ObjectStoreFileStoreAdapter(S3, BUCKET);
    }

    @AfterAll
    static void stopLocalstack() {
        if (S3 != null) {
            S3.close();
        }
        if (LOCALSTACK != null) {
            LOCALSTACK.stop();
        }
    }

    // Same env-var convention as com.npdev.test.postgres.PostgresTestSupport.postgresEnabled() --
    // duplicated rather than shared, since this is the only S3-compatible-engine test that runs by
    // default (HardenObjstoreFileUploadPackagedGeneratedAppRuntimeProofTest's own container use is
    // already excluded from the default gate by NPDevGenerator's packaged-proof filter). The
    // "minio" engine name stays as the stable profile identifier (scripts/policy/local-test-profile.json)
    // even though the backing container is now LocalStack.
    private static boolean minioEnabled() {
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            return true;
        }
        String override = System.getenv("NPDEV_TEST_PROFILE_ENGINES");
        if (override == null) {
            return false;
        }
        return Arrays.stream(override.split(","))
                .map(String::trim)
                .anyMatch(engine -> engine.equalsIgnoreCase("minio"));
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
