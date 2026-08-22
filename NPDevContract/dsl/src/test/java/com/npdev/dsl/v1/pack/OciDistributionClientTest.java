package com.npdev.dsl.v1.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OciDistributionClientTest {

    @TempDir
    Path work;

    @Test
    void fetchesManifestFromFakeRegistry() throws Exception {
        Path contentRoot = work.resolve("registry-content");
        Files.createDirectories(contentRoot);
        FakeOciRegistry registry = FakeOciRegistry.start(contentRoot);
        try {
            String manifestJson = "{\"schemaVersion\":2,\"layers\":[]}";
            registry.addManifest("my-repo", "1.0.0", manifestJson);

            OciDistributionClient client = new OciDistributionClient();
            String result = client.fetchManifest("http://127.0.0.1:" + registry.port(), "my-repo", "1.0.0");

            assertEquals(manifestJson, result);
        } finally {
            registry.stop();
        }
    }

    @Test
    void fetchesBlobFromFakeRegistry() throws Exception {
        Path contentRoot = work.resolve("registry-content");
        Files.createDirectories(contentRoot);
        FakeOciRegistry registry = FakeOciRegistry.start(contentRoot);
        try {
            byte[] blobContent = "hello blob".getBytes(StandardCharsets.UTF_8);
            registry.addBlob("my-repo", "sha256:abc123", blobContent);

            OciDistributionClient client = new OciDistributionClient();
            byte[] result = client.fetchBlob("http://127.0.0.1:" + registry.port(), "my-repo", "sha256:abc123");

            assertArrayEquals(blobContent, result);
        } finally {
            registry.stop();
        }
    }

    @Test
    void missingManifestThrowsIOException() throws Exception {
        Path contentRoot = work.resolve("registry-content");
        Files.createDirectories(contentRoot);
        FakeOciRegistry registry = FakeOciRegistry.start(contentRoot);
        try {
            OciDistributionClient client = new OciDistributionClient();
            IOException failure = assertThrows(IOException.class,
                    () -> client.fetchManifest("http://127.0.0.1:" + registry.port(), "my-repo", "nonexistent"));
            assertTrue(failure.getMessage().contains("not found"), failure.getMessage());
        } finally {
            registry.stop();
        }
    }
}
