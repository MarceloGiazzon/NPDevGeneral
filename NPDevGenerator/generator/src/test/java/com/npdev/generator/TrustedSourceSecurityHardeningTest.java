package com.npdev.generator;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
import com.npdev.generator.emitters.TrustedSourceBytecodeInspector;
import com.npdev.generator.emitters.TrustedSourceEmitter;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedSourceSecurityHardeningTest {

    @Test
    void astPolicyBlocksTrustedProcedureSandboxEscapeVectors() throws Exception {
        assertProcedureRejected("etc-passwd-file-read", """
                import java.nio.file.Files;
                import java.nio.file.Path;

                public final class EscapeProcedure {
                    public Map<String, Object> execute(NPDevProcedureContext ctx) throws Exception {
                        return Map.of("secret", Files.readString(Path.of("/etc/passwd")));
                    }
                }
                """);
        assertProcedureRejected("external-network", """
                import java.util.Map;

                public final class EscapeProcedure {
                    public Map<String, Object> execute(NPDevProcedureContext ctx) throws Exception {
                        new java.net.URL("https://example.com").openStream();
                        return Map.of();
                    }
                }
                """);
        assertProcedureRejected("system-exit", """
                import java.util.Map;

                public final class EscapeProcedure {
                    public Map<String, Object> execute(NPDevProcedureContext ctx) {
                        System.exit(0);
                        return Map.of();
                    }
                }
                """);
        assertProcedureRejected("reflection", """
                import java.util.Map;

                public final class EscapeProcedure {
                    public Map<String, Object> execute(NPDevProcedureContext ctx) throws Exception {
                        Class.forName("java.lang.System");
                        return Map.of();
                    }
                }
                """);
        assertProcedureRejected("classloader", """
                import java.util.Map;

                public final class EscapeProcedure {
                    public Map<String, Object> execute(NPDevProcedureContext ctx) {
                        ClassLoader.getSystemClassLoader();
                        return Map.of();
                    }
                }
                """);
        assertProcedureRejected("process-builder", """
                import java.util.Map;

                public final class EscapeProcedure {
                    public Map<String, Object> execute(NPDevProcedureContext ctx) throws Exception {
                        new ProcessBuilder("sh", "-c", "id").start();
                        return Map.of();
                    }
                }
                """);
    }

    @Test
    void dependencyAndClasspathPolicyRejectsNonAllowlistedImports() throws Exception {
        assertProcedureRejected("third-party-import", """
                import java.util.Map;
                import org.example.UnsafeHelper;

                public final class EscapeProcedure {
                    public Map<String, Object> execute(NPDevProcedureContext ctx) {
                        return Map.of();
                    }
                }
                """);
        assertProcedureRejected("wildcard-import", """
                import java.util.*;

                public final class EscapeProcedure {
                    public Map<String, Object> execute(NPDevProcedureContext ctx) {
                        return Map.of();
                    }
                }
                """);
    }

    @Test
    void trustedPanelSanitizerAndCspRejectActiveEscapeSurface() throws Exception {
        assertPanelRejected("external-fetch", """
                <!doctype html>
                <html><body><script>fetch("https://example.com/steal")</script></body></html>
                """);
        assertPanelRejected("inline-event-handler", """
                <!doctype html>
                <html><body><button onclick="alert(1)">Bad</button></body></html>
                """);
        assertPanelRejected("iframe", """
                <!doctype html>
                <html><body><iframe src="/x"></iframe></body></html>
                """);
    }

    @Test
    void generatedTrustedPanelUsesExternalAssetsAndHardenedCsp() throws Exception {
        Path modelRoot = Files.createTempDirectory("npdev-trusted-source-csp-");
        Path modelPath = modelRoot.resolve("model.json");
        Files.writeString(modelPath, "{}");
        Path panel = modelRoot.resolve("panel/user-admin-panel.html");
        Files.createDirectories(panel.getParent());
        Files.writeString(panel, """
                <!doctype html>
                <html>
                  <head><style>body { color: #111; }</style></head>
                  <body><script>window.NPDev.callProcedure("create-users", {});</script></body>
                </html>
                """);
        Files.writeString(modelRoot.resolve("trusted-source-manifest.json"), panelManifest(sha256(panel)));

        Path out = Files.createTempDirectory("npdev-trusted-source-csp-out-");
        new TrustedSourceEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(panelModel(), modelPath);

        String controller = Files.readString(out.resolve("src/main/java/com/npdev/generated/trusted/GeneratedTrustedSourceRuntimeController.java"));
        assertTrue(controller.contains("object-src 'none'"));
        assertTrue(controller.contains("frame-ancestors 'none'"));
        assertTrue(controller.contains("worker-src 'none'"));
        String html = Files.readString(out.resolve("src/main/resources/trusted-source/panel/user-admin-panel.html"));
        assertTrue(!html.matches("(?s).*<script(?![^>]*\\bsrc\\s*=)[^>]*>.*"));
        assertTrue(!html.contains("<style"));
    }

    @Test
    void productIntegratedBytecodeInspectorAcceptsGeneratedTrustedClassAndRejectsForbiddenOwners() throws Exception {
        Path modelRoot = Files.createTempDirectory("npdev-trusted-bytecode-model-");
        Path modelPath = modelRoot.resolve("model.json");
        Files.writeString(modelPath, "{}");
        Path procedure = modelRoot.resolve("procedure/CreateUsersProcedure.java");
        Files.createDirectories(procedure.getParent());
        Files.writeString(procedure, """
                import java.util.List;
                import java.util.Map;

                public final class CreateUsersProcedure {
                    public Map<String, Object> execute(NPDevProcedureContext ctx) {
                        return Map.of("tenantId", ctx.tenantId(), "users", List.of());
                    }
                }
                """);
        Files.writeString(modelRoot.resolve("trusted-source-manifest.json"), generatedProcedureManifest(sha256(procedure)));

        Path out = Files.createTempDirectory("npdev-trusted-bytecode-out-");
        new TrustedSourceEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(generatedProcedureModel(), modelPath);

        Path proofRoot = bytecodeIntegrationProofRoot();
        Path generatedClassRoot = proofRoot == null
                ? Files.createTempDirectory("npdev-trusted-bytecode-classes-")
                : proofRoot.resolve("generated-classes");
        Files.createDirectories(generatedClassRoot);
        compileJava(
                generatedClassRoot,
                out.resolve("src/main/java/com/npdev/generated/trusted/NPDevProcedureContext.java"),
                out.resolve("src/main/java/com/npdev/generated/trusted/CreateUsersProcedure.java")
        );
        Path generatedClass = generatedClassRoot.resolve("com/npdev/generated/trusted/CreateUsersProcedure.class");
        TrustedSourceBytecodeInspector inspector = new TrustedSourceBytecodeInspector();
        TrustedSourceBytecodeInspector.BytecodeInspectionResult generatedResult = inspector.inspect(generatedClass);
        assertTrue(generatedResult.passed(), generatedResult.violations().toString());

        Path unsafeRoot = proofRoot == null
                ? Files.createTempDirectory("npdev-trusted-bytecode-unsafe-")
                : proofRoot.resolve("unsafe-classes");
        Path unsafeSource = unsafeRoot.resolve("com/npdev/generated/trusted/UnsafeBytecodeProcedure.java");
        Files.createDirectories(unsafeSource.getParent());
        Files.writeString(unsafeSource, """
                package com.npdev.generated.trusted;

                import java.util.Map;

                public final class UnsafeBytecodeProcedure {
                    public Map<String, Object> execute(NPDevProcedureContext ctx) throws Exception {
                        new ProcessBuilder("sh", "-c", "id").start();
                        return Map.of();
                    }
                }
                """);
        compileJava(unsafeRoot, out.resolve("src/main/java/com/npdev/generated/trusted/NPDevProcedureContext.java"), unsafeSource);
        Path unsafeClass = unsafeRoot.resolve("com/npdev/generated/trusted/UnsafeBytecodeProcedure.class");
        TrustedSourceBytecodeInspector.BytecodeInspectionResult unsafeResult = inspector.inspect(unsafeClass);
        assertTrue(!unsafeResult.passed(), unsafeResult.violations().toString());
        writeBytecodeIntegrationProof(generatedClass, generatedResult, unsafeClass, unsafeResult);
    }

    @Test
    void parserBackedPanelSanitizerRejectsOrStripsMalformedBypassStyleHtml() throws Exception {
        assertPanelRejected("svg-onload", """
                <!doctype html>
                <html><body><svg><g onload="alert(1)"></g></svg></body></html>
                """);
        assertPanelRejected("javascript-url-with-entity", """
                <!doctype html>
                <html><body><a href="java&#x0D;script:alert(1)">Bad</a></body></html>
                """);
        assertPanelSanitized("script-src-bypass", """
                <!doctype html>
                <html><body><scr<script>ipt src="//example.com/x.js"></scr<script>ipt></body></html>
                """);
        assertPanelRejected("style-url-bypass", """
                <!doctype html>
                <html><head><style>body { background: url(//example.com/x.png); }</style></head><body></body></html>
                """);
    }

    private static void assertProcedureRejected(String caseName, String source) throws Exception {
        Path modelRoot = Files.createTempDirectory("npdev-trusted-source-" + caseName + "-");
        Path modelPath = modelRoot.resolve("model.json");
        Files.writeString(modelPath, "{}");
        Path procedure = modelRoot.resolve("procedure/EscapeProcedure.java");
        Files.createDirectories(procedure.getParent());
        Files.writeString(procedure, source);
        Files.writeString(modelRoot.resolve("trusted-source-manifest.json"), procedureManifest(sha256(procedure)));

        Path out = Files.createTempDirectory("npdev-trusted-source-out-" + caseName + "-");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new TrustedSourceEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(procedureModel(), modelPath));
        assertTrue(error.getMessage().contains("Forbidden Java source use"), error.getMessage());
    }

    private static void assertPanelRejected(String caseName, String source) throws Exception {
        Path modelRoot = Files.createTempDirectory("npdev-trusted-panel-" + caseName + "-");
        Path modelPath = modelRoot.resolve("model.json");
        Files.writeString(modelPath, "{}");
        Path panel = modelRoot.resolve("panel/user-admin-panel.html");
        Files.createDirectories(panel.getParent());
        Files.writeString(panel, source);
        Files.writeString(modelRoot.resolve("trusted-source-manifest.json"), panelManifest(sha256(panel)));

        Path out = Files.createTempDirectory("npdev-trusted-panel-out-" + caseName + "-");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new TrustedSourceEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(panelModel(), modelPath));
        assertTrue(error.getMessage().contains("Forbidden panel source use"), error.getMessage());
    }

    private static void assertPanelSanitized(String caseName, String source) throws Exception {
        Path modelRoot = Files.createTempDirectory("npdev-trusted-panel-sanitize-" + caseName + "-");
        Path modelPath = modelRoot.resolve("model.json");
        Files.writeString(modelPath, "{}");
        Path panel = modelRoot.resolve("panel/user-admin-panel.html");
        Files.createDirectories(panel.getParent());
        Files.writeString(panel, source);
        Files.writeString(modelRoot.resolve("trusted-source-manifest.json"), panelManifest(sha256(panel)));

        Path out = Files.createTempDirectory("npdev-trusted-panel-sanitize-out-" + caseName + "-");
        new TrustedSourceEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(panelModel(), modelPath);
        String html = Files.readString(out.resolve("src/main/resources/trusted-source/panel/user-admin-panel.html")).toLowerCase();
        assertTrue(!html.contains("javascript:"), html);
        assertTrue(!html.contains("onclick"), html);
        assertTrue(!html.contains("//example.com"), html);
        assertTrue(!html.matches("(?s).*<script(?![^>]*\\bsrc\\s*=)[^>]*>.*"), html);
    }

    private static CompiledModel procedureModel() {
        CompiledProcedure procedure = new CompiledProcedure(
                "escape",
                "",
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of("admin"),
                "summary",
                "write",
                Map.of("trustedSourceEntrypoint", "procedure/EscapeProcedure.java")
        );
        return new CompiledModel(
                "trusted.source.security.procedure",
                "1.0.0",
                "1.0",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(procedure),
                List.of()
        );
    }

    private static CompiledModel generatedProcedureModel() {
        CompiledProcedure procedure = new CompiledProcedure(
                "create-users",
                "",
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of("admin"),
                "summary",
                "write",
                Map.of("trustedSourceEntrypoint", "procedure/CreateUsersProcedure.java")
        );
        return new CompiledModel(
                "trusted.source.security.bytecode",
                "1.0.0",
                "1.0",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(procedure),
                List.of()
        );
    }

    private static CompiledModel panelModel() {
        CompiledPanel panel = new CompiledPanel(
                "user-admin-panel",
                "/users",
                "Users",
                List.of(),
                null,
                List.of(),
                "role:admin",
                "",
                List.of(),
                Map.of(),
                Map.of("trustedSourceEntrypoint", "panel/user-admin-panel.html")
        );
        return new CompiledModel(
                "trusted.source.security.panel",
                "1.0.0",
                "1.0",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(panel)
        );
    }

    private static String procedureManifest(String procedureHash) {
        return """
                {
                  "schemaVersion": "npdev-trusted-source-manifest.v1",
                  "scenarioId": "trusted-source-security",
                  "policyVersion": "cp10",
                  "expectedOutcome": "fail",
                  "entries": [
                    {
                      "entryId": "procedure-escape",
                      "kind": "procedure",
                      "relativePath": "procedure/EscapeProcedure.java",
                      "language": "java",
                      "sha256": "%s",
                      "runtimeBinding": "procedure:escape",
                      "className": "EscapeProcedure",
                      "method": "execute",
                      "requiredRole": "admin",
                      "tenantScoped": true
                    }
                  ]
                }
                """.formatted(procedureHash);
    }

    private static String generatedProcedureManifest(String procedureHash) {
        return """
                {
                  "schemaVersion": "npdev-trusted-source-manifest.v1",
                  "scenarioId": "trusted-source-security-bytecode",
                  "policyVersion": "cp10",
                  "expectedOutcome": "pass",
                  "entries": [
                    {
                      "entryId": "procedure-create-users",
                      "kind": "procedure",
                      "relativePath": "procedure/CreateUsersProcedure.java",
                      "language": "java",
                      "sha256": "%s",
                      "runtimeBinding": "procedure:create-users",
                      "className": "CreateUsersProcedure",
                      "method": "execute",
                      "requiredRole": "admin",
                      "tenantScoped": true
                    }
                  ]
                }
                """.formatted(procedureHash);
    }

    private static String panelManifest(String panelHash) {
        return """
                {
                  "schemaVersion": "npdev-trusted-source-manifest.v1",
                  "scenarioId": "trusted-source-security",
                  "policyVersion": "cp10",
                  "expectedOutcome": "fail",
                  "entries": [
                    {
                      "entryId": "panel-user-admin",
                      "kind": "panel",
                      "relativePath": "panel/user-admin-panel.html",
                      "language": "html+javascript",
                      "sha256": "%s",
                      "runtimeBinding": "panel:/users",
                      "requiredRole": "admin",
                      "tenantScoped": true
                    }
                  ]
                }
                """.formatted(panelHash);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }

    private static void compileJava(Path outputRoot, Path... sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "JDK compiler is required");
        List<String> options = List.of("-encoding", "UTF-8", "-d", outputRoot.toString());
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            List<Path> sourceList = List.of(sources);
            boolean success = compiler.getTask(
                    null,
                    fileManager,
                    null,
                    options,
                    null,
                    fileManager.getJavaFileObjectsFromPaths(sourceList)
            ).call();
            assertTrue(success, "javac should compile trusted-source bytecode proof sources");
        }
    }

    private static void writeBytecodeIntegrationProof(
            Path generatedClass,
            TrustedSourceBytecodeInspector.BytecodeInspectionResult generatedResult,
            Path unsafeClass,
            TrustedSourceBytecodeInspector.BytecodeInspectionResult unsafeResult
    ) throws Exception {
        String proofPath = System.getenv("NPDEV_CP10_BYTECODE_INTEGRATION_PROOF_PATH");
        if (proofPath == null || proofPath.isBlank()) {
            return;
        }
        Path output = Path.of(proofPath);
        Files.createDirectories(output.getParent());
        List<String> violations = new ArrayList<>(unsafeResult.violations());
        String proof = """
                {
                  "schemaVersion": "npdev-trusted-source-bytecode-integration-proof.v1",
                  "generatedTrustedClass": "%s",
                  "generatedTrustedClassPassed": %s,
                  "unsafeTrustedClass": "%s",
                  "unsafeTrustedClassRejected": %s,
                  "unsafeViolationCount": %d,
                  "unsafeViolations": [%s]
                }
                """.formatted(
                slash(generatedClass),
                generatedResult.passed(),
                slash(unsafeClass),
                !unsafeResult.passed(),
                violations.size(),
                quotedJsonArray(violations)
        );
        Files.writeString(output, proof);
    }

    private static Path bytecodeIntegrationProofRoot() throws Exception {
        String proofDir = System.getenv("NPDEV_CP10_BYTECODE_INTEGRATION_DIR");
        if (proofDir == null || proofDir.isBlank()) {
            return null;
        }
        Path root = Path.of(proofDir);
        Files.createDirectories(root);
        return root;
    }

    private static String quotedJsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private static String slash(Path path) {
        return path.toString().replace("\\", "/").replace("\"", "\\\"");
    }
}
