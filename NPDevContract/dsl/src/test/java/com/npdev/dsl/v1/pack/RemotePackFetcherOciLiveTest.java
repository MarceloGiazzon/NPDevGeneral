package com.npdev.dsl.v1.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemotePackFetcherOciLiveTest {

    @TempDir
    Path work;

    @Test
    void endToEndOciFetchAndOfflineRead() throws Exception {
        Path packDir = work.resolve("pack-source");
        Files.createDirectories(packDir);
        Files.writeString(packDir.resolve("pack.json"),
                "{\"pack\":\"identity\",\"version\":\"2.1.0\"}");

        byte[] zipBlob = zipDirectory(packDir);
        String digest = sha256(zipBlob);
        String digestWithPrefix = "sha256:" + digest;

        String manifestJson = "{"
                + "\"schemaVersion\":2,"
                + "\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"layers\":[{"
                + "\"mediaType\":\"application/vnd.npdev.pack.content.v1+zip\","
                + "\"digest\":\"" + digestWithPrefix + "\","
                + "\"size\":" + zipBlob.length
                + "}]}";

        Path registryContent = work.resolve("registry-content");
        Files.createDirectories(registryContent);
        FakeOciRegistry registry = FakeOciRegistry.start(registryContent);
        try {
            registry.addManifest("org/my-pack", "2.1.0", manifestJson);
            registry.addBlob("org/my-pack", digestWithPrefix, zipBlob);

            PackCache cache = new PackCache(work.resolve("cache"));
            String coordinate = "oci://127.0.0.1:" + registry.port() + "/org/my-pack:2.1.0";

            RemotePackFetcher.FetchResult result = RemotePackFetcher.fetch(
                    PackCoordinate.parse(coordinate), NetworkPolicy.ALLOWED, cache);

            assertEquals("{\"pack\":\"identity\",\"version\":\"2.1.0\"}",
                    Files.readString(result.packJson()));
            assertTrue(cache.has(result.digestHex()));

            registry.stop();

            Path offlinePackJson = cache.read(result.digestHex());
            assertNotNull(offlinePackJson);
            assertEquals("{\"pack\":\"identity\",\"version\":\"2.1.0\"}",
                    Files.readString(offlinePackJson));
        } finally {
            registry.stop();
        }
    }

    @Test
    void deniedPolicyRefusesBeforeAnyHttpCall() throws Exception {
        Path registryContent = work.resolve("registry-content");
        Files.createDirectories(registryContent);
        FakeOciRegistry registry = FakeOciRegistry.start(registryContent);
        try {
            PackCache cache = new PackCache(work.resolve("cache"));
            String coordinate = "oci://127.0.0.1:" + registry.port() + "/org/my-pack:1.0.0";

            assertThrows(NetworkPolicyViolationException.class,
                    () -> RemotePackFetcher.fetch(
                            PackCoordinate.parse(coordinate), NetworkPolicy.DENIED, cache));
        } finally {
            registry.stop();
        }
    }

    private static byte[] zipDirectory(Path dir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            try (var walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        String entryName = dir.relativize(file).toString().replace('\\', '/');
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(file, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            }
        }
        return baos.toByteArray();
    }

    private static String sha256(byte[] data) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
