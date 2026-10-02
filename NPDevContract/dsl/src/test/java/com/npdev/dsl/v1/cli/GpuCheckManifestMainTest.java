package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GPU-1 (G2.5/G6): {@code npdev gpu-check plan} runs this against a CANDIDATE model and then reads
 * BOTH the manifest and the per-pack {@code .wgsl} files it writes -- a plan whose shaders were
 * missing silently ran on the CPU (G4). Pins that every pack the manifest declares has its shader.
 */
class GpuCheckManifestMainTest {

    @TempDir
    Path tmp;

    @Test
    void writesTheManifestAndOneShaderPerDeclaredPack() throws Exception {
        Path model = tmp.resolve("model.json");
        Files.writeString(model, """
                {
                  "namespace": "gpu.main.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Invoice",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "total", "type": "decimal", "required": true, "min": 0 }
                      ]
                    }
                  ]
                }
                """);
        Path out = tmp.resolve("out");

        GpuCheckManifestMain.run(model, out);

        JsonNode manifest = new ObjectMapper().readTree(out.resolve("manifest.json").toFile());
        assertEquals("gpu.main.demo", manifest.path("namespace").asText());
        JsonNode packs = manifest.at("/concepts/0/packs");
        assertEquals(1, packs.size());
        Path shader = out.resolve(packs.get(0).path("shader").asText());
        assertTrue(Files.isRegularFile(shader), "every declared pack's shader is written next to the manifest");
        assertTrue(Files.readString(shader).contains("Concept Invoice"));
        assertEquals(4, packs.get(0).at("/columns/0/scale").asInt(), "undeclared decimal scale is D1's 4");
    }
}
