package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 (REAL_LIFT_PLAN_2026-09-03, B13): proves the generator side of a {@code conversions[].javaHook}
 * -- Done-when #1 (admitted through the same bytecode/AST policy a {@code plugin:java-source} mount
 * goes through) and the plugin-runtime manifest registration {@link ConversionHookJavaHookEmitter}
 * does so {@code PluginIpcChildProcessPool}'s existing null-when-empty bean condition "just works".
 * The RUNTIME half (the hook actually running in the pooled child, resuming past a crash) is proven
 * separately against a real H2 database by NPDevRuntimeHost's {@code ConversionHookRunner} tests.
 */
class ConversionHookEmitterJavaHookTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CompiledModel compile(String json) throws IOException {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new ModelCompiler().compile(ast);
    }

    private static final String MODEL_JSON = """
            {
              "namespace": "b1.javahook.test",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "priorityNumber", "type": "integer" },
                    { "name": "orderSummary", "type": "string" }
                ] }
              ],
              "conversions": [
                { "id": "0001-java-hook-summary", "concept": "Order",
                  "javaHook": {
                    "source": "conversion-hooks/summary/src/main/java",
                    "class": "com.npdev.samples.b1test.SummaryHook",
                    "method": "summarize"
                  },
                  "claims": [ "orderSummary" ] }
              ]
            }
            """;

    private static final String HOOK_SOURCE = """
            package com.npdev.samples.b1test;

            import java.util.Map;

            public final class SummaryHook {
                public Map<String, Object> summarize(Map<String, Object> input) {
                    return input;
                }
            }
            """;

    /** Sets up {@code <tempDir>/Input/conversion-hooks/summary/src/main/java/.../SummaryHook.java}
     *  and returns a {@code modelSourcePath} (never itself read -- {@link #compile} already parsed
     *  the model from the string above) whose parent is {@code <tempDir>/Input}, matching how {@code
     *  ConversionHookEmitter} resolves a javaHook's {@code source} relative to the real model's
     *  definition directory. */
    private static Path writeHookSource(Path tempDir, String className, String source) throws IOException {
        Path definitionDir = tempDir.resolve("Input");
        Path sourceFile = definitionDir.resolve("conversion-hooks/summary/src/main/java")
                .resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        return definitionDir.resolve("model.json");
    }

    @Test
    void admitsAndRegistersAJavaHookConversion(@TempDir Path tempDir) throws IOException {
        Path modelSourcePath = writeHookSource(tempDir, "com.npdev.samples.b1test.SummaryHook", HOOK_SOURCE);
        CompiledModel model = compile(MODEL_JSON);
        Path outRoot = tempDir.resolve("App");

        new ConversionHookEmitter().emit(model, modelSourcePath, outRoot);

        // hook.json: claims + javaHook{class,method}, no convert.sql at all.
        Path hookDir = outRoot.resolve("src/main/resources/db/conversion-hooks/0001-java-hook-summary");
        String hookJson = Files.readString(hookDir.resolve("hook.json"), StandardCharsets.UTF_8);
        assertTrue(hookJson.contains("ADD_REQUIRED_COLUMN:orders:order_summary"), hookJson);
        assertTrue(hookJson.contains("com.npdev.samples.b1test.SummaryHook"), hookJson);
        assertTrue(hookJson.contains("\"summarize\""), hookJson);
        assertFalse(Files.exists(hookDir.resolve("convert.sql")), "javaHook must not emit a convert.sql");

        // The Java source was copied into the app's own compiled source tree.
        Path copiedSource = outRoot.resolve("src/main/java/com/npdev/samples/b1test/SummaryHook.java");
        assertTrue(Files.isRegularFile(copiedSource), "expected copied hook source at " + copiedSource);
        assertEquals(HOOK_SOURCE, Files.readString(copiedSource, StandardCharsets.UTF_8));

        // The boot-time bytecode gate's class list carries the hook's compiled class.
        String bytecodeGateList = Files.readString(
                outRoot.resolve("src/main/resources/npdev/plugin-bytecode/plugin-owned-classes.txt"),
                StandardCharsets.UTF_8);
        assertTrue(bytecodeGateList.contains("com/npdev/samples/b1test/SummaryHook.class"), bytecodeGateList);

        // java-source-runtime-refs.json: the pooled child resolves mainClass/method from here.
        JsonNode runtimeRefs = MAPPER.readTree(outRoot.resolve(
                "src/main/resources/npdev/plugin-runtime/java-source-runtime-refs.json").toFile());
        JsonNode entry = null;
        for (JsonNode candidate : runtimeRefs.path("javaSourcePlugins")) {
            if ("0001-java-hook-summary".equals(candidate.path("runtimeRef").asText())) {
                entry = candidate;
            }
        }
        assertTrue(entry != null, "no java-source-runtime-refs.json entry for the javaHook conversion");
        assertEquals("com.npdev.samples.b1test.SummaryHook", entry.path("mainClass").asText());
        assertEquals("plugin:java-migration-hook", entry.path("adapterId").asText());
        assertEquals("summarize", entry.path("methodByOperation").path("summarize").asText());

        // plugin-manifest.json (all three profile variants): one adapter row under one pluginId --
        // the invoke contribution. No declared-callback row: the child's method return value carries
        // the write back to the host directly, so there is nothing for the child to call back for.
        for (String fileName : new String[] {
                "default.plugin-manifest.json", "dev.plugin-manifest.json", "warning.plugin-manifest.json" }) {
            JsonNode manifest = MAPPER.readTree(
                    outRoot.resolve("src/main/resources/npdev/plugins/" + fileName).toFile());
            JsonNode plugin = null;
            for (JsonNode candidate : manifest.path("plugins")) {
                if ("conversion-hook-0001-java-hook-summary".equals(candidate.path("pluginId").asText())) {
                    plugin = candidate;
                }
            }
            assertTrue(plugin != null, fileName + ": no plugin entry for the javaHook conversion");
            assertEquals(1, plugin.path("adapters").size(), fileName + ": expected exactly 1 adapter row");
            JsonNode adapter = plugin.path("adapters").get(0);
            assertEquals("conversionHook:0001-java-hook-summary", adapter.path("capability").asText());
            assertEquals("summarize", adapter.path("operation").asText());
            assertEquals("plugin:java-migration-hook", adapter.path("adapterId").asText());
        }
    }

    /** Done-when #1: the SAME bytecode/AST escape-detection a {@code plugin:java-source} mount goes
     *  through -- a hook trying to shell out must be refused at GENERATION time, never baked into an
     *  app that then fails the boot gate. */
    @Test
    void rejectsAJavaHookSourceThatEscapesTheSandbox(@TempDir Path tempDir) throws IOException {
        String evilSource = """
                package com.npdev.samples.b1test;

                import java.util.Map;

                public final class SummaryHook {
                    public Map<String, Object> summarize(Map<String, Object> input) throws Exception {
                        new ProcessBuilder("sh", "-c", "id").start();
                        return input;
                    }
                }
                """;
        Path modelSourcePath = writeHookSource(tempDir, "com.npdev.samples.b1test.SummaryHook", evilSource);
        CompiledModel model = compile(MODEL_JSON);
        Path outRoot = tempDir.resolve("App");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ConversionHookEmitter().emit(model, modelSourcePath, outRoot));
        assertTrue(exception.getMessage().contains("Forbidden plugin Java source use"), exception.getMessage());
    }

    @Test
    void claimsResolveAgainstTheRealConceptFieldsSameX0DisciplineAsEveryOtherOp(@TempDir Path tempDir) throws IOException {
        Path modelSourcePath = writeHookSource(tempDir, "com.npdev.samples.b1test.SummaryHook", HOOK_SOURCE);
        String json = MODEL_JSON.replace("\"claims\": [ \"orderSummary\" ]", "\"claims\": [ \"noSuchField\" ]");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> compile(json));
        assertTrue(exception.getMessage().contains("noSuchField"), exception.getMessage());
        assertTrue(exception.getMessage().contains("0001-java-hook-summary"), exception.getMessage());
    }
}
