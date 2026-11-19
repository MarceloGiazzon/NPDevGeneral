package com.npdev.generator;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.emitters.UntrustedExtensionEmitter;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-342: a hash-pinned Java class is bound to a FLOW ({@code flows[].metadata.untrustedExtensionEntrypoint}, manifest
 * entry kind {@code flow}) instead of a {@code procedures[]} entry. The flow keeps only the schema-required placeholder
 * step; the generated trusted class and its registry entry carry the flow's name.
 */
class UntrustedExtensionEmitterFlowKindTest {

    private static final String MODEL = """
            {
              "namespace": "flow.kind.demo",
              "dslVersion": "3.0.0",
              "version": "1.0",
              "concepts": [
                { "name": "Thing", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "name", "type": "string", "required": true } ] }
              ],
              "flows": [
                {
                  "name": "RegisterThingTrusted",
                  "description": "Placeholder steps; the hash-pinned Java class runs instead.",
                  "steps": [ { "name": "placeholder", "type": "return", "value": "$input" } ],
                  "metadata": { "untrustedExtensionEntrypoint": "trusted-source/RegisterThingTrusted.java" }
                }
              ]
            }
            """;

    private static final String JAVA = """
            import java.util.Map;

            public final class RegisterThingTrusted {
                public Map<String, Object> execute(NPDevProcedureContext ctx) {
                    return Map.of("ok", true);
                }
            }
            """;

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static String manifest(String hash, String kind) {
        return """
                {
                  "schemaVersion": "npdev-untrusted-extension-manifest.v1",
                  "scenarioId": "flow-kind",
                  "policyVersion": "test",
                  "expectedOutcome": "pass",
                  "entries": [
                    {
                      "entryId": "flow-register-thing-trusted",
                      "kind": "%s",
                      "relativePath": "trusted-source/RegisterThingTrusted.java",
                      "language": "java",
                      "sha256": "%s",
                      "runtimeBinding": "flow:RegisterThingTrusted",
                      "className": "RegisterThingTrusted",
                      "method": "execute",
                      "requiredRole": "ADMIN",
                      "tenantScoped": true
                    }
                  ]
                }
                """.formatted(kind, hash);
    }

    private static Path stage(String manifestKind, boolean withManifestEntry) throws Exception {
        Path root = Files.createTempDirectory("npdev-flow-kind-");
        Path java = root.resolve("trusted-source/RegisterThingTrusted.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, JAVA);
        Files.writeString(root.resolve("model.json"), MODEL);
        String manifest = withManifestEntry
                ? manifest(sha256(java), manifestKind)
                : "{ \"schemaVersion\": \"npdev-untrusted-extension-manifest.v1\", \"entries\": [] }";
        Files.writeString(root.resolve("untrusted-extension-manifest.json"), manifest);
        return root.resolve("model.json");
    }

    private static void emit(Path modelPath, Path out) throws Exception {
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        new UntrustedExtensionEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(model, modelPath);
    }

    @Test
    void aFlowNamingAnExtensionGeneratesTheTrustedClassAndARegistryEntryUnderTheFlowName() throws Exception {
        Path modelPath = stage("flow", true);
        Path out = Files.createTempDirectory("npdev-flow-kind-out-");
        emit(modelPath, out);

        Path trusted = out.resolve("src/main/java/com/npdev/generated/trusted");
        assertTrue(Files.isRegularFile(trusted.resolve("RegisterThingTrusted.java")), "the pinned class is generated");
        String registry = Files.readString(trusted.resolve("GeneratedActionRegistry.java"));
        assertTrue(registry.contains("\"RegisterThingTrusted\""), "the registry names the flow");
        assertTrue(registry.contains("\"ADMIN\""), "the role comes from the manifest entry");
    }

    @Test
    void theFlowMetadataSurvivesParseAndCompile() throws Exception {
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(stage("flow", true)));
        assertEquals("trusted-source/RegisterThingTrusted.java",
                model.getFlows().get(0).getMetadata().get("untrustedExtensionEntrypoint"));
    }

    @Test
    void aFlowNamingAnExtensionWithNoManifestEntryFailsClosed() throws Exception {
        Path modelPath = stage("flow", false);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> emit(modelPath, Files.createTempDirectory("npdev-flow-kind-out-")));
        assertTrue(failure.getMessage().contains("no manifest entry"), failure.getMessage());
    }

    @Test
    void aProcedureKindEntryDoesNotSatisfyAFlowReference() throws Exception {
        Path modelPath = stage("procedure", true);
        assertThrows(IllegalStateException.class,
                () -> emit(modelPath, Files.createTempDirectory("npdev-flow-kind-out-")));
    }
}
