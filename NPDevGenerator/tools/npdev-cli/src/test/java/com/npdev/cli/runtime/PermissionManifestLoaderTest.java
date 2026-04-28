package com.npdev.cli.runtime;

import com.npdev.kernel.security.PermissionGrant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionManifestLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadPermissionManifest() throws Exception {
        Path file = tempDir.resolve("dev.permissions.json");
        Files.writeString(file, """
{
  "grants": [
    {
      "permission": "flow.execute",
      "tenantId": "tenant-a",
      "actorId": "",
      "role": "admin"
    }
  ]
}
""", StandardCharsets.UTF_8);

        List<PermissionGrant> grants = new PermissionManifestLoader().load(file);

        assertEquals(1, grants.size());
        assertEquals("flow.execute", grants.get(0).permission());
        assertEquals("tenant-a", grants.get(0).tenantId());
        assertEquals("admin", grants.get(0).role());
    }
}
