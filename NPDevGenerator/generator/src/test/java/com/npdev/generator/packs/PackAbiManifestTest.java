package com.npdev.generator.packs;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackAbiManifestTest {

    @Test
    void roundTripsThroughProperties() {
        PackAbiManifest original = new PackAbiManifest("identity", "1.0.0", 1, "1");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        original.writeTo(out);

        PackAbiManifest roundTripped = PackAbiManifest.readFrom(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(original, roundTripped);
    }

    @Test
    void packageNameIsPackScopedAndVersioned() {
        PackAbiManifest manifest = new PackAbiManifest("identity", "2.3.1", 2, "1");

        assertEquals("com.npdev.pack.identity.v2", manifest.packageName());
    }
}
