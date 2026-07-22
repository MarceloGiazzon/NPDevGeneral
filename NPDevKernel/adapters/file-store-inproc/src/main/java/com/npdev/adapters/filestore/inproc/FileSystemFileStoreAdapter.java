package com.npdev.adapters.filestore.inproc;

import com.npdev.kernel.ports.FileHandle;
import com.npdev.kernel.ports.FileStoreContract;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * LIFT-UPLOAD-P1: filesystem-backed {@link FileStoreContract} for dev/InMemory apps -- the
 * {@code *-inproc} half of the adapter pair (the object-store half is deferred; see
 * BOUNDARY_LIFT_ROADMAP.md). Writes live under an explicitly configured root that the caller
 * must point outside the repo/Build tree (this adapter does not choose or default that path,
 * consistent with the platform's build-output policy).
 *
 * <p>Keys are {@code <sanitized-tenantId>/<uuid>}, so a malicious/odd tenantId can never escape
 * the configured root via path traversal (sanitization strips anything but
 * {@code [A-Za-z0-9_-]}).
 */
public final class FileSystemFileStoreAdapter implements FileStoreContract {
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^A-Za-z0-9_-]");
    private static final String DEFAULT_STORE_ID = "file-store-inproc";

    private final Path root;
    private final String storeId;

    public FileSystemFileStoreAdapter(Path root) {
        this(root, DEFAULT_STORE_ID);
    }

    public FileSystemFileStoreAdapter(Path root, String storeId) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.storeId = (storeId == null || storeId.isBlank()) ? DEFAULT_STORE_ID : storeId.trim();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create file-store root: " + this.root, e);
        }
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
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target)) {
                content.transferTo(out);
            }
            long actualSize = Files.size(target);
            String safeType = safeContentType(contentType);
            String safeOriginalName = safeName(originalName);
            writeMeta(target, safeType, safeOriginalName);
            return new FileHandle(storeId, key, safeType, actualSize, safeOriginalName);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file for tenant " + tenantId, e);
        }
    }

    @Override
    public void get(FileHandle handle, OutputStream destination) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(destination, "destination");
        Path source = resolve(handle.key());
        if (!Files.exists(source)) {
            throw new NoSuchElementException("No stored bytes for handle key: " + handle.key());
        }
        try (InputStream in = Files.newInputStream(source)) {
            in.transferTo(destination);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read stored file for key " + handle.key(), e);
        }
    }

    @Override
    public FileHandle head(String requestedStoreId, String key) {
        Objects.requireNonNull(key, "key");
        Path target = resolve(key);
        if (!Files.exists(target)) {
            throw new NoSuchElementException("No stored bytes for key: " + key);
        }
        Properties meta = readMeta(target);
        try {
            long actualSize = Files.size(target);
            return new FileHandle(
                    storeId, key,
                    meta.getProperty("contentType", "application/octet-stream"),
                    actualSize,
                    meta.getProperty("originalName", "file")
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stat stored file for key " + key, e);
        }
    }

    @Override
    public void delete(FileHandle handle) {
        if (handle == null) {
            return;
        }
        Path target = resolve(handle.key());
        try {
            Files.deleteIfExists(target);
            Files.deleteIfExists(metaPath(target));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete stored file for key " + handle.key(), e);
        }
    }

    @Override
    public boolean exists(FileHandle handle) {
        return handle != null && Files.exists(resolve(handle.key()));
    }

    @Override
    public List<String> listTenants() {
        List<String> tenants = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return tenants;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path candidate : stream) {
                if (Files.isDirectory(candidate)) {
                    tenants.add(candidate.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list tenants under file-store root: " + root, e);
        }
        return tenants;
    }

    @Override
    public List<StoredObject> list(String tenantId) {
        List<StoredObject> out = new ArrayList<>();
        Path tenantDir = root.resolve(sanitize(tenantId, "default"));
        if (!Files.isDirectory(tenantDir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tenantDir)) {
            for (Path candidate : stream) {
                String fileName = candidate.getFileName().toString();
                if (fileName.endsWith(".meta") || !Files.isRegularFile(candidate)) {
                    continue;
                }
                String key = root.relativize(candidate).toString().replace('\\', '/');
                Instant uploadedAt = Files.getLastModifiedTime(candidate).toInstant();
                out.add(new StoredObject(key, uploadedAt));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list stored objects for tenant " + tenantId, e);
        }
        return out;
    }

    private static Path metaPath(Path contentPath) {
        return contentPath.resolveSibling(contentPath.getFileName() + ".meta");
    }

    private static void writeMeta(Path contentPath, String contentType, String originalName) throws IOException {
        Properties meta = new Properties();
        meta.setProperty("contentType", contentType);
        meta.setProperty("originalName", originalName);
        try (OutputStream out = Files.newOutputStream(metaPath(contentPath))) {
            meta.store(out, null);
        }
    }

    private static Properties readMeta(Path contentPath) {
        Properties meta = new Properties();
        Path sidecar = metaPath(contentPath);
        if (Files.exists(sidecar)) {
            try (InputStream in = Files.newInputStream(sidecar)) {
                meta.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read metadata sidecar: " + sidecar, e);
            }
        }
        return meta;
    }

    private Path resolve(String key) {
        // Every key this adapter itself issues is already sanitized (see put()); this guard is a
        // defense-in-depth check against a handle constructed/round-tripped by a caller.
        Path candidate = root.resolve(key).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("File handle key escapes the configured store root: " + key);
        }
        return candidate;
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
}
