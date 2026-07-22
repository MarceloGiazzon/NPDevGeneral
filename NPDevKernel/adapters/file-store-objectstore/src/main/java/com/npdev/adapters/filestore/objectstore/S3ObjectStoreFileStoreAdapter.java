package com.npdev.adapters.filestore.objectstore;

import com.npdev.kernel.ports.FileHandle;
import com.npdev.kernel.ports.FileStoreContract;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * HARDEN-OBJSTORE-P1: {@link FileStoreContract} against S3-compatible object storage (AWS S3,
 * MinIO, Cloudflare R2, GCS' S3-compat surface) -- the production half of the locked file-storage
 * decision (dev = {@code file-store-inproc}, prod = this adapter). Same key scheme and tenant
 * scoping as the inproc adapter (see
 * {@link com.npdev.adapters.filestore.inproc.FileSystemFileStoreAdapter file-store-inproc's
 * adapter}), so the two are interchangeable behind {@link FileStoreContract} with no controller
 * changes.
 *
 * <p>Large uploads stream via S3 multipart-upload so nothing beyond one part ({@link
 * #PART_SIZE_BYTES}) is ever buffered in memory; small uploads (under one part) go through a
 * single {@code PutObject} call instead, since starting a multipart upload for a 1KB file is pure
 * overhead.
 */
public final class S3ObjectStoreFileStoreAdapter implements FileStoreContract {
    private static final String DEFAULT_STORE_ID = "file-store-objectstore";
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^A-Za-z0-9_-]");
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_-]+/[A-Za-z0-9_-]+");
    private static final String ORIGINAL_NAME_METADATA_KEY = "npdev-original-name";
    static final int PART_SIZE_BYTES = 8 * 1024 * 1024; // 8MB: S3's minimum multipart part size is 5MB

    private final S3Client s3Client;
    private final String bucket;
    private final String storeId;

    public S3ObjectStoreFileStoreAdapter(S3Client s3Client, String bucket) {
        this(s3Client, bucket, DEFAULT_STORE_ID);
    }

    public S3ObjectStoreFileStoreAdapter(S3Client s3Client, String bucket, String storeId) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        this.bucket = requireNonBlank(bucket, "bucket");
        this.storeId = (storeId == null || storeId.isBlank()) ? DEFAULT_STORE_ID : storeId.trim();
    }

    @Override
    public FileHandle put(
            String tenantId,
            String originalName,
            String contentType,
            long sizeBytes,
            InputStream content
    ) {
        Objects.requireNonNull(content, "content");
        String tenantSegment = sanitize(tenantId, "default");
        String key = tenantSegment + "/" + UUID.randomUUID();
        String safeType = safeContentType(contentType);
        String safeOriginalName = safeName(originalName);
        Map<String, String> metadata = Map.of(ORIGINAL_NAME_METADATA_KEY, encodeMetadataValue(safeOriginalName));

        try {
            long actualSize = streamToS3(key, safeType, metadata, content);
            return new FileHandle(storeId, key, safeType, actualSize, safeOriginalName);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file for tenant " + tenantId, e);
        }
    }

    private long streamToS3(String key, String contentType, Map<String, String> metadata, InputStream content)
            throws IOException {
        byte[] firstChunk = readFully(content, PART_SIZE_BYTES);
        if (firstChunk.length < PART_SIZE_BYTES) {
            // Fits in a single part: skip multipart entirely.
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).metadata(metadata).build(),
                    RequestBody.fromBytes(firstChunk)
            );
            return firstChunk.length;
        }

        CreateMultipartUploadResponse created = s3Client.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(bucket).key(key).contentType(contentType).metadata(metadata).build());
        String uploadId = created.uploadId();
        List<CompletedPart> parts = new ArrayList<>();
        long totalBytes = 0;
        try {
            int partNumber = 1;
            byte[] chunk = firstChunk;
            while (chunk.length > 0) {
                UploadPartResponse partResponse = s3Client.uploadPart(
                        UploadPartRequest.builder().bucket(bucket).key(key).uploadId(uploadId)
                                .partNumber(partNumber).contentLength((long) chunk.length).build(),
                        RequestBody.fromBytes(chunk)
                );
                parts.add(CompletedPart.builder().partNumber(partNumber).eTag(partResponse.eTag()).build());
                totalBytes += chunk.length;
                partNumber++;
                chunk = readFully(content, PART_SIZE_BYTES);
            }
            s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build());
            return totalBytes;
        } catch (RuntimeException e) {
            s3Client.abortMultipartUpload(builder -> builder.bucket(bucket).key(key).uploadId(uploadId));
            throw e;
        }
    }

    /** Reads up to {@code maxBytes} from {@code in}, looping on short reads; never buffers more than one part. */
    private static byte[] readFully(InputStream in, int maxBytes) throws IOException {
        byte[] buffer = new byte[maxBytes];
        int total = 0;
        while (total < maxBytes) {
            int read = in.read(buffer, total, maxBytes - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total == maxBytes ? buffer : java.util.Arrays.copyOf(buffer, total);
    }

    @Override
    public void get(FileHandle handle, OutputStream destination) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(destination, "destination");
        String key = requireValidKey(handle.key());
        try {
            s3Client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build(),
                    ResponseTransformer.toOutputStream(destination)
            );
        } catch (NoSuchKeyException e) {
            throw new NoSuchElementException("No stored bytes for handle key: " + key);
        }
    }

    @Override
    public FileHandle head(String requestedStoreId, String key) {
        String validatedKey = requireValidKey(key);
        try {
            HeadObjectResponse response = s3Client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(validatedKey).build());
            String originalName = decodeMetadataValue(
                    response.metadata().getOrDefault(ORIGINAL_NAME_METADATA_KEY, "file"));
            String contentType = response.contentType() == null || response.contentType().isBlank()
                    ? "application/octet-stream" : response.contentType();
            return new FileHandle(storeId, validatedKey, contentType, response.contentLength(), originalName);
        } catch (NoSuchKeyException e) {
            throw new NoSuchElementException("No stored bytes for key: " + key);
        }
    }

    @Override
    public void delete(FileHandle handle) {
        if (handle == null) {
            return;
        }
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(handle.key()).build());
    }

    @Override
    public boolean exists(FileHandle handle) {
        if (handle == null) {
            return false;
        }
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(handle.key()).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public List<String> listTenants() {
        List<String> tenants = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                    .bucket(bucket).delimiter("/");
            if (continuationToken != null) {
                requestBuilder.continuationToken(continuationToken);
            }
            ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
            for (var commonPrefix : response.commonPrefixes()) {
                String prefix = commonPrefix.prefix();
                if (prefix != null && prefix.endsWith("/")) {
                    tenants.add(prefix.substring(0, prefix.length() - 1));
                }
            }
            continuationToken = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (continuationToken != null);
        return tenants;
    }

    @Override
    public List<StoredObject> list(String tenantId) {
        List<StoredObject> out = new ArrayList<>();
        String prefix = sanitize(tenantId, "default") + "/";
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix);
            if (continuationToken != null) {
                requestBuilder.continuationToken(continuationToken);
            }
            ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
            for (var s3Object : response.contents()) {
                out.add(new StoredObject(s3Object.key(), s3Object.lastModified()));
            }
            continuationToken = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (continuationToken != null);
        return out;
    }

    private static String requireValidKey(String key) {
        Objects.requireNonNull(key, "key");
        // Every key this adapter issues is already `<sanitized-tenant>/<uuid>` (see put()); this
        // guard rejects a handle constructed/round-tripped by a caller with a malformed key.
        if (!VALID_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("File handle key has an unexpected shape: " + key);
        }
        return key;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value.trim();
    }

    private static String sanitize(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        String cleaned = UNSAFE_CHARS.matcher(trimmed).replaceAll("_");
        return cleaned.isBlank() ? fallback : cleaned.toLowerCase(Locale.ROOT);
    }

    private static String safeContentType(String contentType) {
        return (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType.trim();
    }

    private static String safeName(String originalName) {
        return (originalName == null || originalName.isBlank()) ? "file" : originalName.trim();
    }

    private static String encodeMetadataValue(String value) {
        // S3 user-metadata values travel as HTTP headers; URL-encode so non-ASCII filenames
        // round-trip safely instead of being mangled or rejected by the SDK/service.
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decodeMetadataValue(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
