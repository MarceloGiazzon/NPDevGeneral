package com.npdev.adapters.filestore.objectstore;

import com.npdev.kernel.ports.FileHandle;
import com.npdev.kernel.ports.FileStoreContract.StoredObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HARDEN-OBJSTORE-P1: adapter logic against a mocked {@link S3Client} (no live endpoint needed). */
class S3ObjectStoreFileStoreAdapterTest {

    @Test
    void smallPutGoesThroughASinglePutObjectCallNotMultipart() {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        S3ObjectStoreFileStoreAdapter adapter = new S3ObjectStoreFileStoreAdapter(s3, "npdev-files");

        byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
        FileHandle handle = adapter.put("tenant-a", "greeting.txt", "text/plain", bytes.length,
                new ByteArrayInputStream(bytes));

        assertTrue(handle.key().startsWith("tenant-a/"));
        assertEquals("text/plain", handle.contentType());
        assertEquals(bytes.length, handle.sizeBytes());
        assertEquals("greeting.txt", handle.originalName());
        verify(s3, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    }

    @Test
    void largePutStreamsThroughMultipartUploadWithoutBufferingTheWholeFile() {
        S3Client s3 = mock(S3Client.class);
        when(s3.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenReturn(UploadPartResponse.builder().eTag("etag").build());
        S3ObjectStoreFileStoreAdapter adapter = new S3ObjectStoreFileStoreAdapter(s3, "npdev-files");

        int size = S3ObjectStoreFileStoreAdapter.PART_SIZE_BYTES * 2 + 1024; // spans 3 parts
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i % 251);
        }

        FileHandle handle = adapter.put("tenant-a", "big.bin", "application/octet-stream", size,
                new ByteArrayInputStream(bytes));

        assertEquals(size, handle.sizeBytes());
        ArgumentCaptor<UploadPartRequest> partCaptor = ArgumentCaptor.forClass(UploadPartRequest.class);
        verify(s3, times(3)).uploadPart(partCaptor.capture(), any(RequestBody.class));
        assertEquals(1, partCaptor.getAllValues().get(0).partNumber());
        assertEquals(2, partCaptor.getAllValues().get(1).partNumber());
        assertEquals(3, partCaptor.getAllValues().get(2).partNumber());
        verify(s3, times(1)).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        verify(s3, never()).abortMultipartUpload(any(java.util.function.Consumer.class));
    }

    @Test
    void headResolvesTheStoredContentTypeAndOriginalNameFromS3Metadata() {
        S3Client s3 = mock(S3Client.class);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentType("text/html")
                .contentLength(42L)
                .metadata(java.util.Map.of("npdev-original-name", "payload.html"))
                .build());
        S3ObjectStoreFileStoreAdapter adapter = new S3ObjectStoreFileStoreAdapter(s3, "npdev-files");

        FileHandle resolved = adapter.head("file-store-objectstore", "tenant-a/some-uuid");

        assertEquals("text/html", resolved.contentType());
        assertEquals("payload.html", resolved.originalName());
        assertEquals(42L, resolved.sizeBytes());
    }

    @Test
    void headOnAMissingKeyThrowsNoSuchElement() {
        S3Client s3 = mock(S3Client.class);
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        S3ObjectStoreFileStoreAdapter adapter = new S3ObjectStoreFileStoreAdapter(s3, "npdev-files");

        assertThrows(NoSuchElementException.class, () -> adapter.head("file-store-objectstore", "tenant-a/missing"));
    }

    @Test
    void getMapsNoSuchKeyToNoSuchElement() {
        S3Client s3 = mock(S3Client.class);
        when(s3.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class),
                any(ResponseTransformer.class)))
                .thenThrow(NoSuchKeyException.builder().build());
        S3ObjectStoreFileStoreAdapter adapter = new S3ObjectStoreFileStoreAdapter(s3, "npdev-files");
        FileHandle handle = new FileHandle("file-store-objectstore", "tenant-a/missing", "text/plain", 0, "x.txt");

        assertThrows(NoSuchElementException.class, () -> adapter.get(handle, new ByteArrayOutputStream()));
    }

    @Test
    void listTenantsReadsCommonPrefixesFromADelimitedListing() {
        S3Client s3 = mock(S3Client.class);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .commonPrefixes(
                        CommonPrefix.builder().prefix("tenant-a/").build(),
                        CommonPrefix.builder().prefix("tenant-b/").build())
                .isTruncated(false)
                .build());
        S3ObjectStoreFileStoreAdapter adapter = new S3ObjectStoreFileStoreAdapter(s3, "npdev-files");

        List<String> tenants = adapter.listTenants();

        assertEquals(List.of("tenant-a", "tenant-b"), tenants);
    }

    @Test
    void listReadsObjectsUnderTheTenantPrefixWithUploadTimestamps() {
        S3Client s3 = mock(S3Client.class);
        Instant uploadedAt = Instant.parse("2026-07-01T00:00:00Z");
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("tenant-a/uuid-1").lastModified(uploadedAt).build())
                .isTruncated(false)
                .build());
        S3ObjectStoreFileStoreAdapter adapter = new S3ObjectStoreFileStoreAdapter(s3, "npdev-files");

        List<StoredObject> objects = adapter.list("tenant-a");

        assertEquals(1, objects.size());
        assertEquals("tenant-a/uuid-1", objects.get(0).key());
        assertEquals(uploadedAt, objects.get(0).uploadedAt());

        ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3).listObjectsV2(captor.capture());
        assertEquals("tenant-a/", captor.getValue().prefix());
    }

    @Test
    void malformedKeyIsRejectedDefensively() {
        S3Client s3 = mock(S3Client.class);
        S3ObjectStoreFileStoreAdapter adapter = new S3ObjectStoreFileStoreAdapter(s3, "npdev-files");
        FileHandle malicious = new FileHandle("file-store-objectstore", "../outside", "text/plain", 0, "x.txt");

        assertThrows(IllegalArgumentException.class, () -> adapter.get(malicious, new ByteArrayOutputStream()));
    }
}
