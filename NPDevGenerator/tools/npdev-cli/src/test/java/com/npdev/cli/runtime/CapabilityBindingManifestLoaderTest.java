package com.npdev.cli.runtime;

import com.npdev.kernel.capabilities.CapabilityBindingManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CapabilityBindingManifestLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadBindingManifest() throws Exception {
        Path file = tempDir.resolve("dev.bindings.json");
        Files.writeString(file, """
{
  "bindings": [
    {
      "capability": "EmailCapability",
      "capabilityType": "NotificationCapability",
      "adapterId": "smtp-dev",
      "adapterClass": "com.example.EmailAdapter",
      "environment": "dev",
      "tenantId": "tenant-a"
    }
  ]
}
""", StandardCharsets.UTF_8);

        CapabilityBindingManifest manifest = new CapabilityBindingManifestLoader().load(file);

        assertEquals(1, manifest.bindings().size());
        assertEquals("emailcapability", manifest.bindings().get(0).capability());
        assertEquals("notificationcapability", manifest.bindings().get(0).capabilityType());
        assertEquals("smtp-dev", manifest.bindings().get(0).adapterId());
    }
}
