package com.finalexec.npdev.service.pluginipc;

import com.npdev.kernel.security.TrustedSourceBytecodeInspector;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * SEC-10 (B30 lift): computes the classpath a plugin IPC child process actually needs, instead of
 * the host's own full runtime classpath {@link PluginIpcChildProcessPool}/{@link PluginIpcChildProcess}
 * defaulted to before this package -- the one line ({@code
 * PluginIpcChildProcessPool.java}'s {@code System.getProperty("java.class.path")} default) the
 * boundary's own residue named responsible for "a pooled worker runs on the host's own full
 * classpath."
 *
 * <p>The plan this package was built from assumed a per-plugin {@code libraries[]} declaration to
 * consult (a {@code JavaSourceRuntimeRefManifest} field that does not exist anywhere in the DSL,
 * schemas, or manifest -- traced, not assumed). No such declaration mechanism exists: a
 * {@code plugin:java-source}/{@code plugin:java-controller} class is an ordinary POJO compiled
 * INTO the same {@code sourceSets.main} output as every other generated/RuntimeHost-template class
 * (confirmed against a real generated app's {@code build.gradle}: {@code srcDir 'src/main/java'}
 * and {@code srcDir 'npdev-generated/src/main/java'} compile to ONE output), so there is no
 * per-plugin dependency list to read. What this computes instead is CONTENT-based: keep a classpath
 * entry only if it contains something the child genuinely needs to start and run at all -- {@link
 * PluginIpcChildProcessMain} itself, the kernel API types, Jackson (the wire codec), the app's own
 * compiled output (where the plugin class physically lives, mixed in with everything else), and --
 * only when at least one {@code plugin:java-controller} mount exists -- Spring's web-annotation
 * classes a generated controller's own method parameters declare. Everything else (ORM, DB
 * drivers, messaging adapters, most of Spring, the rest of the platform's own adapter jars) is
 * dropped. This is a REAL reduction (a real generated app's classpath commonly has 40-80 entries)
 * but is deliberately not the ONLY defense: the app's own compiled output still carries {@code
 * com.finalexec.*} and non-plugin {@code com.npdev.generated.*} classes alongside the plugin class,
 * since they cannot be physically split -- {@link PluginRestrictedClassLoader} is what actually
 * refuses those at class-load time; this class only removes UNRELATED third-party jars.</p>
 *
 * <p><b>2026-09-06 fix: a plugin's OWN third-party dependency (E8, Guava) was being dropped too.</b>
 * The fixed marker list above only recognizes PLATFORM classes -- it has no way to know a specific
 * plugin's business logic calls into some other library (there is still no {@code libraries[]}
 * manifest declaring it, the same absence established above). Every {@code plugin:java-source}/
 * {@code plugin:java-controller} mount's own compiled class (named by
 * {@code JavaSourceRuntimeRefManifest}/{@code PluginControllerRouteManifest}, already loaded at this
 * call site for other reasons) is now scanned with {@link TrustedSourceBytecodeInspector}'s
 * constant-pool reader -- the SAME reader the B30 admission gates already use, just asking a
 * different question ("what does this reference" instead of "does this violate policy") -- and every
 * class it references becomes an ADDITIONAL marker, so the classpath entry actually holding that
 * library is kept alongside the fixed platform set.</p>
 */
public final class PluginChildClasspath {

    private static final String MARKER_CHILD_MAIN = "com/finalexec/npdev/service/pluginipc/PluginIpcChildProcessMain.class";
    private static final String MARKER_APP_OWN_OUTPUT = "com/finalexec/FinalExecApplication.class";
    private static final String MARKER_KERNEL = "com/npdev/kernel/CapabilityCall.class";
    private static final String MARKER_JACKSON_DATABIND = "com/fasterxml/jackson/databind/ObjectMapper.class";
    private static final String MARKER_JACKSON_CORE = "com/fasterxml/jackson/core/JsonParser.class";
    private static final String MARKER_JACKSON_ANNOTATIONS = "com/fasterxml/jackson/annotation/JsonProperty.class";
    private static final String MARKER_SPRING_WEB_ANNOTATIONS = "org/springframework/web/bind/annotation/PathVariable.class";

    private static final String BOOT_INF_CLASSES = "BOOT-INF/classes/";
    private static final String BOOT_INF_LIB = "BOOT-INF/lib/";

    private PluginChildClasspath() {
    }

    private static List<String> markers(boolean anyControllerMounts) {
        List<String> markers = new ArrayList<>(List.of(
                MARKER_CHILD_MAIN, MARKER_APP_OWN_OUTPUT, MARKER_KERNEL,
                MARKER_JACKSON_DATABIND, MARKER_JACKSON_CORE, MARKER_JACKSON_ANNOTATIONS
        ));
        if (anyControllerMounts) {
            markers.add(MARKER_SPRING_WEB_ANNOTATIONS);
        }
        return markers;
    }

    /**
     * Computes the restricted classpath. When {@code hostClasspath} is a single executable Spring
     * Boot archive (a real deployed app, {@code java -jar FinalExec.jar} -- SEC-5's own fat-jar
     * shape), the needed content is extracted from inside it to a temp directory, since a nested
     * {@code BOOT-INF/lib/*.jar} entry has no standalone filesystem path a plain {@code -cp} can
     * reference. Returns a classpath STRING (already joined with {@link File#pathSeparator}), ready
     * to hand to {@link PluginIpcChildProcess}'s plain {@code -cp} branch -- the fat-jar case no
     * longer needs {@code PropertiesLauncher} once its content is extracted onto a normal,
     * multi-entry classpath.
     *
     * @param pluginOwnedClassNames every mounted {@code plugin:java-source}/{@code plugin:java-controller}
     *         class's dotted FQCN (from {@code JavaSourceRuntimeRefManifest.Entry#mainClass}/
     *         {@code PluginControllerRouteManifest.Entry#controllerClassName}) -- scanned for
     *         third-party classes the plugin's own code genuinely references (class javadoc above).
     */
    public static String compute(String hostClasspath, boolean anyControllerMounts,
            List<String> pluginOwnedClassNames) throws IOException {
        List<String> needed = new ArrayList<>(markers(anyControllerMounts));
        if (isSingleJarPath(hostClasspath) && PluginIpcChildProcess.isExecutableSpringBootArchive(hostClasspath)) {
            needed.addAll(referencedMarkersFromFatJar(Path.of(hostClasspath), pluginOwnedClassNames));
            return String.join(File.pathSeparator, extractFromFatJar(Path.of(hostClasspath), needed));
        }
        needed.addAll(referencedMarkersFromClasspath(hostClasspath, pluginOwnedClassNames));
        List<String> kept = new ArrayList<>();
        for (String entry : hostClasspath.split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank() && entryHasAnyMarker(Path.of(entry), needed)) {
                kept.add(entry);
            }
        }
        return String.join(File.pathSeparator, kept);
    }

    /**
     * Every class a plugin-owned class's OWN compiled bytecode references (Guava's {@code Hashing},
     * say), as classpath-resource markers ({@code "com/google/common/hash/Hashing.class"}) -- a
     * plugin class always lives in exactly one classpath entry (the app's own compiled output, per
     * this class's own javadoc), so the first entry found holding it is scanned and the search stops.
     * Includes the class's {@code $}-sibling files (inner/anonymous classes are separate class
     * files), matching {@code PluginBytecodeBootGate}'s own sibling-expansion for the same reason.
     */
    private static List<String> referencedMarkersFromClasspath(String hostClasspath, List<String> pluginOwnedClassNames)
            throws IOException {
        List<String> markers = new ArrayList<>();
        for (String dottedClassName : pluginOwnedClassNames) {
            String basePath = dottedClassName.replace('.', '/');
            for (String entryPath : hostClasspath.split(Pattern.quote(File.pathSeparator))) {
                if (entryPath.isBlank()) {
                    continue;
                }
                Path entry = Path.of(entryPath);
                List<String> resourcePaths = pluginClassResourcePaths(entry, basePath);
                if (resourcePaths.isEmpty()) {
                    continue;
                }
                for (String resourcePath : resourcePaths) {
                    byte[] classBytes = readEntryBytes(entry, resourcePath);
                    if (classBytes != null) {
                        markers.addAll(referencedClassMarkers(classBytes, resourcePath));
                    }
                }
                break;
            }
        }
        return markers;
    }

    /** The fat-jar twin of {@link #referencedMarkersFromClasspath}: a plugin class always lives
     *  under {@code BOOT-INF/classes/}, never inside a nested {@code BOOT-INF/lib/*.jar}. */
    private static List<String> referencedMarkersFromFatJar(Path fatJarPath, List<String> pluginOwnedClassNames)
            throws IOException {
        if (pluginOwnedClassNames.isEmpty()) {
            return List.of();
        }
        List<String> markers = new ArrayList<>();
        try (JarFile jarFile = new JarFile(fatJarPath.toFile())) {
            for (String dottedClassName : pluginOwnedClassNames) {
                String basePath = dottedClassName.replace('.', '/');
                String topLevel = BOOT_INF_CLASSES + basePath + ".class";
                if (jarFile.getEntry(topLevel) == null) {
                    continue;
                }
                List<String> resourcePaths = new ArrayList<>();
                resourcePaths.add(topLevel);
                resourcePaths.addAll(matchingJarEntries(jarFile, BOOT_INF_CLASSES, basePath));
                for (String resourcePath : resourcePaths) {
                    java.util.zip.ZipEntry entry = jarFile.getEntry(resourcePath);
                    try (InputStream in = jarFile.getInputStream(entry)) {
                        markers.addAll(referencedClassMarkers(in.readAllBytes(), resourcePath));
                    }
                }
            }
        }
        return markers;
    }

    /** The class file at {@code basePath + ".class"} plus any {@code $}-sibling in the same entry,
     *  or empty if this entry does not hold that class at all. */
    private static List<String> pluginClassResourcePaths(Path entry, String basePath) throws IOException {
        String topLevel = basePath + ".class";
        if (Files.isDirectory(entry)) {
            if (!Files.exists(entry.resolve(topLevel))) {
                return List.of();
            }
            List<String> resolved = new ArrayList<>();
            resolved.add(topLevel);
            int lastSlash = basePath.lastIndexOf('/');
            String packageDir = lastSlash < 0 ? "" : basePath.substring(0, lastSlash);
            String base = lastSlash < 0 ? basePath : basePath.substring(lastSlash + 1);
            Path packagePath = packageDir.isEmpty() ? entry : entry.resolve(packageDir);
            if (Files.isDirectory(packagePath)) {
                try (var files = Files.list(packagePath)) {
                    files.map(p -> p.getFileName().toString())
                            .filter(name -> name.startsWith(base + "$") && name.endsWith(".class"))
                            .forEach(name -> resolved.add((packageDir.isEmpty() ? "" : packageDir + "/") + name));
                }
            }
            return resolved;
        }
        if (!Files.isRegularFile(entry)) {
            return List.of();
        }
        try (JarFile jarFile = new JarFile(entry.toFile())) {
            if (jarFile.getEntry(topLevel) == null) {
                return List.of();
            }
            List<String> resolved = new ArrayList<>();
            resolved.add(topLevel);
            resolved.addAll(matchingJarEntries(jarFile, "", basePath));
            return resolved;
        } catch (IOException notAJar) {
            return List.of();
        }
    }

    /** {@code $}-sibling entries of {@code prefix + basePath} inside an already-open jar (top-level
     *  match excluded -- the caller already knows the top-level class exists). */
    private static List<String> matchingJarEntries(JarFile jarFile, String prefix, String basePath) {
        int lastSlash = basePath.lastIndexOf('/');
        String packagePrefix = prefix + (lastSlash < 0 ? "" : basePath.substring(0, lastSlash + 1));
        String base = lastSlash < 0 ? basePath : basePath.substring(lastSlash + 1);
        List<String> matches = new ArrayList<>();
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(packagePrefix + base + "$") && name.endsWith(".class")) {
                matches.add(name);
            }
        }
        return matches;
    }

    private static byte[] readEntryBytes(Path entry, String resourcePath) throws IOException {
        if (Files.isDirectory(entry)) {
            Path file = entry.resolve(resourcePath);
            return Files.exists(file) ? Files.readAllBytes(file) : null;
        }
        if (!Files.isRegularFile(entry)) {
            return null;
        }
        try (JarFile jarFile = new JarFile(entry.toFile())) {
            java.util.zip.ZipEntry jarEntry = jarFile.getEntry(resourcePath);
            if (jarEntry == null) {
                return null;
            }
            try (InputStream in = jarFile.getInputStream(jarEntry)) {
                return in.readAllBytes();
            }
        } catch (IOException notAJar) {
            return null;
        }
    }

    private static List<String> referencedClassMarkers(byte[] classBytes, String displayName) throws IOException {
        try (InputStream in = new ByteArrayInputStream(classBytes)) {
            return new TrustedSourceBytecodeInspector().referencedClassNames(in, displayName).stream()
                    .map(owner -> owner + ".class")
                    .toList();
        }
    }

    private static boolean isSingleJarPath(String classpath) {
        return !classpath.contains(File.pathSeparator);
    }

    private static boolean entryHasAnyMarker(Path entry, List<String> markers) {
        if (Files.isDirectory(entry)) {
            for (String marker : markers) {
                if (Files.exists(entry.resolve(marker))) {
                    return true;
                }
            }
            return false;
        }
        if (!Files.isRegularFile(entry)) {
            return false;
        }
        try (JarFile jarFile = new JarFile(entry.toFile())) {
            for (String marker : markers) {
                if (jarFile.getEntry(marker) != null) {
                    return true;
                }
            }
            return false;
        } catch (IOException notAJar) {
            return false;
        }
    }

    /**
     * Extracts {@code BOOT-INF/classes/} wholesale (the app's own compiled output -- there is no
     * finer-grained split available, see the class javadoc) into one directory, and extracts only
     * the {@code BOOT-INF/lib/*.jar} entries that contain a needed marker into standalone temp jar
     * files. Each nested jar is checked by extracting it to a temp file first and opening THAT as an
     * ordinary {@link JarFile} -- a nested jar's bytes are not independently addressable inside the
     * outer zip without being materialized somewhere first.
     */
    private static List<String> extractFromFatJar(Path fatJarPath, List<String> needed) throws IOException {
        Path extractedRoot = Files.createTempDirectory("npdev-plugin-child-cp-");
        Path classesDir = extractedRoot.resolve("classes");
        Files.createDirectories(classesDir);
        List<String> classpathEntries = new ArrayList<>();
        classpathEntries.add(classesDir.toString());

        try (JarFile jarFile = new JarFile(fatJarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                if (name.startsWith(BOOT_INF_CLASSES)) {
                    Path target = classesDir.resolve(name.substring(BOOT_INF_CLASSES.length()));
                    Files.createDirectories(target.getParent());
                    copyEntry(jarFile, entry, target);
                } else if (name.startsWith(BOOT_INF_LIB) && name.endsWith(".jar")) {
                    Path candidateJar = extractedRoot.resolve("lib-candidate.jar");
                    copyEntry(jarFile, entry, candidateJar);
                    if (jarHasAnyMarker(candidateJar, needed)) {
                        Path keptJar = extractedRoot.resolve(sanitizeFileName(name.substring(BOOT_INF_LIB.length())));
                        Files.move(candidateJar, keptJar, StandardCopyOption.REPLACE_EXISTING);
                        classpathEntries.add(keptJar.toString());
                    } else {
                        Files.deleteIfExists(candidateJar);
                    }
                }
            }
        }
        return classpathEntries;
    }

    private static boolean jarHasAnyMarker(Path jarPath, List<String> markers) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            for (String marker : markers) {
                if (jarFile.getEntry(marker) != null) {
                    return true;
                }
            }
            return false;
        } catch (IOException notAJar) {
            return false;
        }
    }

    private static void copyEntry(JarFile jarFile, JarEntry entry, Path target) throws IOException {
        try (InputStream input = jarFile.getInputStream(entry);
             OutputStream output = Files.newOutputStream(target)) {
            input.transferTo(output);
        }
    }

    private static String sanitizeFileName(String nestedJarName) {
        // BOOT-INF/lib/ entries are already flat filenames (Spring Boot's repackager never nests a
        // directory under lib/), but strip any stray path separator defensively rather than trust it.
        int lastSlash = Math.max(nestedJarName.lastIndexOf('/'), nestedJarName.lastIndexOf('\\'));
        return lastSlash < 0 ? nestedJarName : nestedJarName.substring(lastSlash + 1);
    }
}
