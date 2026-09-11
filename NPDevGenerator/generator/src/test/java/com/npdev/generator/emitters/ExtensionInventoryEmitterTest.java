package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path A P0.3: proves {@link ExtensionInventoryEmitter} writes extension-inventory.json and counts
 * a real {@code conversions[].javaHook} correctly, while reporting zero for the three mechanisms
 * this minimal model does not use ({@code TrustedSourceManifest.referencesFrom} and
 * {@link GeneratedPluginMountPlan#fromModelSource} both degrade gracefully -- no trusted-source
 * manifest and no model source file on disk -- rather than throwing, which is what lets this test
 * avoid standing up a full plugin/trusted-source fixture).
 */
class ExtensionInventoryEmitterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL_JSON = """
            {
              "namespace": "p0.3.inventory.test",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "orderSummary", "type": "string" }
                ] }
              ],
              "conversions": [
                { "id": "0001-java-hook-summary", "concept": "Order",
                  "javaHook": {
                    "source": "conversion-hooks/summary/src/main/java",
                    "class": "com.npdev.samples.p03test.SummaryHook",
                    "method": "summarize"
                  },
                  "claims": [ "orderSummary" ] }
              ]
            }
            """;

    private static CompiledModel model() throws IOException {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL_JSON));
        return new ModelCompiler().compile(ast);
    }

    @Test
    void emitsAWellFormedInventoryCountingTheOnlyRealJavaHook(@TempDir Path tempDir) throws IOException {
        Path outRoot = tempDir.resolve("Output");
        Files.createDirectories(outRoot);
        Path modelSourcePath = tempDir.resolve("Input").resolve("model.json");

        new ExtensionInventoryEmitter(new GeneratedSourceWriter(outRoot, new RegenerationPolicy()))
                .emit(model(), null, modelSourcePath);

        Path inventoryPath = outRoot.resolve("src/main/resources/npdev/extension-inventory.json");
        assertTrue(Files.isRegularFile(inventoryPath), "extension-inventory.json must be written");

        JsonNode inventory = MAPPER.readTree(Files.readString(inventoryPath));
        assertEquals("1.0", inventory.path("schemaVersion").asText());

        JsonNode counts = inventory.path("counts");
        assertEquals(1, counts.path("javaHook").asInt());
        assertEquals(0, counts.path("trustedSourceAsset").asInt());
        assertEquals(0, counts.path("inProcessController").asInt());
        assertEquals(0, counts.path("pluginPackage").asInt());

        JsonNode entries = inventory.path("entries");
        assertEquals(1, entries.size());
        JsonNode entry = entries.get(0);
        assertEquals("javaHook", entry.path("category").asText());
        assertEquals("conversionJavaHook", entry.path("kind").asText());
        assertEquals("0001-java-hook-summary", entry.path("owner").asText());
        assertTrue(entry.path("origin").asText().contains("SummaryHook.java#summarize"));
    }
}
