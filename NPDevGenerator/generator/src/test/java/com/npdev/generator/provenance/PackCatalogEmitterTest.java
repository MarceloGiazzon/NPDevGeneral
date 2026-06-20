package com.npdev.generator.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs from the real generator module working directory, so it exercises the real
 * {@code NPDevContract/packs} discovery the same way {@code BuiltinPackComposerTest} does.
 */
final class PackCatalogEmitterTest {

    @TempDir
    Path tempDir;

    @Test
    void catalogsRealBuiltinPacksAndFlagsIncludedWhenInternalTablesEnabled() throws Exception {
        GeneratedSourceWriter writer = new GeneratedSourceWriter(tempDir, new RegenerationPolicy());
        new PackCatalogEmitter().emit(writer, true);

        JsonNode root = new ObjectMapper().readTree(tempDir.resolve(PackCatalogEmitter.RELATIVE_PATH).toFile());
        assertTrue(root.path("discoverable").asBoolean());

        JsonNode identity = findPack(root.path("packs"), "identity");
        assertEquals("1.0.0", identity.path("version").asText());
        assertTrue(identity.path("conceptCount").asInt() >= 3);
        assertTrue(identity.path("included").asBoolean());

        JsonNode workspace = findPack(root.path("packs"), "workspace");
        assertTrue(workspace.path("included").asBoolean());
    }

    @Test
    void marksPacksNotIncludedWhenInternalTablesDisabled() throws Exception {
        GeneratedSourceWriter writer = new GeneratedSourceWriter(tempDir, new RegenerationPolicy());
        new PackCatalogEmitter().emit(writer, false);

        JsonNode root = new ObjectMapper().readTree(tempDir.resolve(PackCatalogEmitter.RELATIVE_PATH).toFile());
        JsonNode identity = findPack(root.path("packs"), "identity");
        assertEquals(false, identity.path("included").asBoolean());
    }

    private static JsonNode findPack(JsonNode packs, String alias) {
        for (JsonNode pack : packs) {
            if (alias.equals(pack.path("alias").asText())) {
                return pack;
            }
        }
        throw new AssertionError("No pack found for alias " + alias);
    }
}
