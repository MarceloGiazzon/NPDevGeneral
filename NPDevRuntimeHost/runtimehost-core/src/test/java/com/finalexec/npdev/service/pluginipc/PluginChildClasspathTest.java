package com.finalexec.npdev.service.pluginipc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SEC-10 (B30 lift): {@link PluginChildClasspath#compute} against real, synthetic classpath entries. */
class PluginChildClasspathTest {

    @Test
    void keepsOnlyEntriesThatContainANeededMarkerClass(@TempDir Path tempDir) throws IOException {
        Path neededJar = writeJarWithEntry(tempDir.resolve("child-main.jar"),
                "com/finalexec/npdev/service/pluginipc/PluginIpcChildProcessMain.class");
        Path appDir = tempDir.resolve("app-classes");
        Files.createDirectories(appDir.resolve("com/finalexec"));
        Files.createFile(appDir.resolve("com/finalexec/FinalExecApplication.class"));
        Path unrelatedJar = writeJarWithEntry(tempDir.resolve("hibernate-core.jar"),
                "org/hibernate/Session.class");

        String hostClasspath = String.join(
                File.pathSeparator, neededJar.toString(), appDir.toString(), unrelatedJar.toString());

        String restricted = PluginChildClasspath.compute(hostClasspath, false, List.of());

        List<String> kept = List.of(restricted.split(java.util.regex.Pattern.quote(File.pathSeparator)));
        assertTrue(kept.contains(neededJar.toString()), "the entry holding PluginIpcChildProcessMain must be kept");
        assertTrue(kept.contains(appDir.toString()), "the app's own compiled-classes entry must be kept");
        assertFalse(kept.contains(unrelatedJar.toString()), "an unrelated dependency jar must be dropped");
    }

    @Test
    void keepsTheSpringWebAnnotationsJarOnlyWhenAControllerMountExists(@TempDir Path tempDir) throws IOException {
        Path springWebJar = writeJarWithEntry(tempDir.resolve("spring-web.jar"),
                "org/springframework/web/bind/annotation/PathVariable.class");
        String hostClasspath = springWebJar.toString();

        assertTrue(PluginChildClasspath.compute(hostClasspath, true, List.of()).contains(springWebJar.toString()),
                "spring-web must be kept when a plugin:java-controller mount exists");
        assertEquals("", PluginChildClasspath.compute(hostClasspath, false, List.of()),
                "spring-web must be dropped when no plugin:java-controller mount exists");
    }

    @Test
    void extractsOnlyNeededContentFromAnExecutableSpringBootArchive(@TempDir Path tempDir) throws IOException {
        Path nestedNeededJar = writeJarWithEntry(tempDir.resolve("nested-kernel.jar"),
                "com/npdev/kernel/CapabilityCall.class");
        Path nestedUnneededJar = writeJarWithEntry(tempDir.resolve("nested-hibernate.jar"),
                "org/hibernate/Session.class");
        Path fatJar = tempDir.resolve("FinalExec.jar");
        writeFatJar(fatJar, nestedNeededJar, nestedUnneededJar);

        String restricted = PluginChildClasspath.compute(fatJar.toString(), false, List.of());
        List<String> kept = List.of(restricted.split(java.util.regex.Pattern.quote(File.pathSeparator)));

        assertTrue(kept.size() >= 2, "expected an extracted classes dir plus at least the kept nested jar, got: " + kept);
        boolean hasExtractedClasses = kept.stream().anyMatch(entry ->
                Files.exists(Path.of(entry).resolve("com/finalexec/npdev/service/pluginipc/PluginIpcChildProcessMain.class")));
        assertTrue(hasExtractedClasses, "BOOT-INF/classes/ content must be extracted, kept: " + kept);
        boolean hasKeptNestedJar = kept.stream().anyMatch(entry -> {
            try {
                return new java.util.jar.JarFile(entry).getEntry("com/npdev/kernel/CapabilityCall.class") != null;
            } catch (IOException e) {
                return false;
            }
        });
        assertTrue(hasKeptNestedJar, "the needed nested jar must be extracted and kept, kept: " + kept);
        boolean hasUnneededNestedJar = kept.stream().anyMatch(entry -> {
            try {
                return new java.util.jar.JarFile(entry).getEntry("org/hibernate/Session.class") != null;
            } catch (IOException e) {
                return false;
            }
        });
        assertFalse(hasUnneededNestedJar, "an unneeded nested dependency jar must not be extracted, kept: " + kept);
    }

    @Test
    void keepsAThirdPartyJarAPluginOwnClassGenuinelyReferences(@TempDir Path tempDir) throws Exception {
        // E8 regression (2026-09-06): the fixed marker list has no way to know a plugin's own
        // business logic calls into some OTHER library (Guava, in the real E8 probe) -- there is no
        // libraries[] manifest declaring it. Standing in for that library here with a REAL jar
        // already on this JVM's own classpath (org.junit.jupiter.api, deliberately NOT one of
        // PluginChildClasspath's fixed markers) rather than a synthetic one, so the constant-pool
        // scan runs against genuine bytecode, not a hand-rolled fixture.
        Path pluginClassDir = tempDir.resolve("app-classes");
        Files.createDirectories(pluginClassDir.resolve("com/finalexec"));
        Files.createFile(pluginClassDir.resolve("com/finalexec/FinalExecApplication.class"));
        compileInto(pluginClassDir, "com/example/plugin/UserCapability.java", """
                package com.example.plugin;

                import org.junit.jupiter.api.Assertions;

                public final class UserCapability {
                    public void run() {
                        Assertions.assertTrue(true);
                    }
                }
                """);

        Path thirdPartyJar = Path.of(
                org.junit.jupiter.api.Assertions.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path unrelatedJar = writeJarWithEntry(tempDir.resolve("hibernate-core.jar"), "org/hibernate/Session.class");

        String hostClasspath = String.join(File.pathSeparator,
                pluginClassDir.toString(), thirdPartyJar.toString(), unrelatedJar.toString());

        String restricted = PluginChildClasspath.compute(hostClasspath, false, List.of("com.example.plugin.UserCapability"));
        List<String> kept = List.of(restricted.split(java.util.regex.Pattern.quote(File.pathSeparator)));

        assertTrue(kept.contains(thirdPartyJar.toString()),
                "a jar the plugin's own class genuinely references, but which is not a fixed platform marker, must be kept: " + kept);
        assertFalse(kept.contains(unrelatedJar.toString()), "a jar the plugin does not reference must still be dropped");
    }

    private static void compileInto(Path outputRoot, String relativeSource, String source) throws Exception {
        Path sourceRoot = Files.createTempDirectory("npdev-plugin-cp-test-src-");
        Path sourceFile = sourceRoot.resolve(relativeSource);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        List<String> options = List.of("-encoding", "UTF-8", "-d", outputRoot.toString(), "-proc:none",
                "-classpath", System.getProperty("java.class.path"));
        try (javax.tools.StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            Iterable<? extends javax.tools.JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromPaths(List.of(sourceFile));
            boolean ok = Boolean.TRUE.equals(compiler.getTask(null, fileManager, null, options, null, units).call());
            if (!ok) {
                throw new IllegalStateException("Test fixture failed to compile: " + relativeSource);
            }
        }
    }

    private static Path writeJarWithEntry(Path jarPath, String entryName) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new ZipEntry(entryName));
            jar.write(new byte[]{1, 2, 3});
            jar.closeEntry();
        }
        return jarPath;
    }

    /** Minimal fat-jar shape {@link PluginIpcChildProcess#isExecutableSpringBootArchive} and
     * {@link PluginChildClasspath}'s own extractor both recognize: a Boot loader class present at
     * top level, BOOT-INF/classes/ holding the app's own compiled output, BOOT-INF/lib/ holding
     * nested dependency jars verbatim (Spring Boot never re-compresses a nested jar's own bytes). */
    private static void writeFatJar(Path fatJarPath, Path neededNestedJar, Path unneededNestedJar) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(fatJarPath), manifest)) {
            jar.putNextEntry(new ZipEntry("org/springframework/boot/loader/launch/PropertiesLauncher.class"));
            jar.write(new byte[]{1, 2, 3});
            jar.closeEntry();

            jar.putNextEntry(new ZipEntry("BOOT-INF/classes/com/finalexec/npdev/service/pluginipc/PluginIpcChildProcessMain.class"));
            jar.write(new byte[]{1, 2, 3});
            jar.closeEntry();
            jar.putNextEntry(new ZipEntry("BOOT-INF/classes/com/finalexec/FinalExecApplication.class"));
            jar.write(new byte[]{1, 2, 3});
            jar.closeEntry();

            addNestedJarVerbatim(jar, "BOOT-INF/lib/kernel-0.1.0.jar", neededNestedJar);
            addNestedJarVerbatim(jar, "BOOT-INF/lib/hibernate-core-6.0.0.jar", unneededNestedJar);
        }
    }

    private static void addNestedJarVerbatim(JarOutputStream jar, String entryName, Path nestedJarPath) throws IOException {
        jar.putNextEntry(new ZipEntry(entryName));
        try (OutputStream out = new java.io.FilterOutputStream(jar) {
            @Override
            public void close() {
                // JarOutputStream.closeEntry() below closes the ENTRY, not the whole stream --
                // suppress the underlying close() a plain OutputStream contract would otherwise call.
            }
        }) {
            Files.copy(nestedJarPath, out);
        }
        jar.closeEntry();
    }
}
