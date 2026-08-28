package com.finalexec.config;

import com.finalexec.boundary.BoundaryBootException;
import com.finalexec.boundary.BoundaryViolation;
import com.npdev.kernel.security.TrustedSourceBytecodeInspector;
import org.springframework.beans.factory.InitializingBean;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Logger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * SEC-3 / B30 boot admission gate: refuses app start when any plugin-owned class file's compiled
 * bytecode references a capability escape (filesystem/network IO, process/system control,
 * reflection, dynamic loading, threads, scripting, detached async work, JVM internals) -- the
 * printed-form twin of the generator's source-level admission
 * ({@code PluginJavaSourcePolicy}, NPDevGenerator), sharing the same kernel denylist
 * ({@link TrustedSourceBytecodeInspector}).
 *
 * <p><b>What it reads.</b> The per-app manifest the generator writes,
 * {@code npdev/plugin-bytecode/plugin-owned-classes.txt} (one classpath resource path per mounted
 * plugin class). Absent manifest = no mounted plugins = gate is a no-op. For each listed class it
 * also scans the class's {@code $}-siblings ({@code Foo$1.class}, {@code Foo$Inner.class}), because
 * inner/anonymous classes compile to SEPARATE class files whose bytecode is invisible to a
 * top-level-only listing -- the source-level gate still blocks those at generation time by walking
 * the whole source file, but a compiled-only barrier must not have that hole.
 *
 * <p><b>Refusal shape.</b> Any violation is thrown as a {@link BoundaryBootException} carrying
 * {@code B30:plugin_bytecode_violation:} (FinalExecApplication converts that into exit code 4 and
 * the {@code npdev why B30} failure analysis). This matches NPDev's boot-time-refusal boundary
 * convention (B4/B5/B9 style).
 *
 * <p><b>What it does NOT do.</b> It does not bound memory/CPU, cannot interrupt an
 * uninterruptible loop (only the wall-clock timeout exists for that), and only sees DIRECT
 * references -- anything on the app classpath that proxies a forbidden capability on a plugin's
 * behalf is outside its view. It is an admission check, not a sandbox (B30 boundary statement).
 */
public final class PluginBytecodeBootGate implements InitializingBean {

    private static final Logger LOG = Logger.getLogger(PluginBytecodeBootGate.class.getName());

    /**
     * Must equal {@code RuntimeApiEmitter.emitPluginBytecodeGateManifestIfNeeded}'s destination
     * (NPDevGenerator) -- npdev-plugin-bytecode-gate twin-pair rule
     * (scripts/quality/twin-pair-registry.json) pins the two literals together.
     */
    static final String PLUGIN_OWNED_CLASSES_RESOURCE = "npdev/plugin-bytecode/plugin-owned-classes.txt";

    static final String BOUNDARY_CODE = "B30:plugin_bytecode_violation:";

    private final ClassLoader classLoader;

    public PluginBytecodeBootGate() {
        this(Thread.currentThread().getContextClassLoader());
    }

    /** Testable seam: a custom classloader over a temp tree of compiled plugin class files. */
    public PluginBytecodeBootGate(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> violations = scan();
        if (!violations.isEmpty()) {
            throw new BoundaryBootException(
                    new BoundaryViolation("B30", "boot", BOUNDARY_CODE + " " + String.join("; ", violations), Instant.now())
            );
        }
    }

    List<String> scan() {
        List<String> listedClasses = readPluginOwnedClasses();
        if (listedClasses.isEmpty()) {
            return List.of();
        }
        TrustedSourceBytecodeInspector inspector = new TrustedSourceBytecodeInspector();
        List<String> violations = new ArrayList<>();
        for (String listedClass : listedClasses) {
            for (String resourcePath : resolveCompiledClassResources(listedClass)) {
                try (InputStream in = classLoader.getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        LOG.warning("Plugin-owned class listed in " + PLUGIN_OWNED_CLASSES_RESOURCE + " is not on the classpath: " + resourcePath);
                        continue;
                    }
                    TrustedSourceBytecodeInspector.BytecodeInspectionResult result = inspector.inspect(in, resourcePath);
                    if (!result.passed()) {
                        for (String violation : result.violations()) {
                            violations.add(resourcePath + ": " + violation);
                        }
                    }
                } catch (IOException ex) {
                    violations.add(resourcePath + ": unreadable (" + ex.getMessage() + ")");
                }
            }
        }
        return List.copyOf(violations);
    }

    private List<String> readPluginOwnedClasses() {
        try (InputStream in = classLoader.getResourceAsStream(PLUGIN_OWNED_CLASSES_RESOURCE)) {
            if (in == null) {
                return List.of();
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read " + PLUGIN_OWNED_CLASSES_RESOURCE, ex);
        }
    }

    /**
     * Expands one listed top-level class into itself plus any compiled {@code $}-siblings in the
     * same package (inner/anonymous classes are separate class files). Enumerates the package
     * directory via the classloader so it works for exploded classes dirs AND jars.
     */
    private List<String> resolveCompiledClassResources(String listedClass) {
        List<String> resolved = new ArrayList<>();
        resolved.add(listedClass);
        int lastSlash = listedClass.lastIndexOf('/');
        int lastDot = listedClass.lastIndexOf('.');
        if (lastSlash < 0 || lastDot <= lastSlash) {
            return resolved;
        }
        String packageDir = listedClass.substring(0, lastSlash);
        String base = listedClass.substring(lastSlash + 1, lastDot);
        try {
            Enumeration<URL> packageUrls = classLoader.getResources(packageDir);
            while (packageUrls.hasMoreElements()) {
                URL packageUrl = packageUrls.nextElement();
                resolved.addAll(matchingSiblingClassFiles(packageUrl, packageDir, base));
            }
        } catch (IOException ex) {
            LOG.warning("Unable to enumerate plugin-owned package " + packageDir + " for " + listedClass + ": " + ex.getMessage());
        }
        return resolved.stream().distinct().toList();
    }

    private static List<String> matchingSiblingClassFiles(URL packageUrl, String packageDir, String base) {
        List<String> matches = new ArrayList<>();
        try {
            if ("file".equals(packageUrl.getProtocol())) {
                Path directory = Path.of(packageUrl.toURI());
                if (Files.isDirectory(directory)) {
                    try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
                        entries.map(path -> path.getFileName().toString())
                                .filter(name -> name.equals(base + ".class")
                                        || (name.startsWith(base + "$") && name.endsWith(".class")))
                                .forEach(name -> matches.add(packageDir + "/" + name));
                    }
                }
            } else if ("jar".equals(packageUrl.getProtocol())) {
                try (JarFile jarFile = ((JarURLConnection) packageUrl.openConnection()).getJarFile()) {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    String packagePrefix = packageDir + "/";
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (!name.startsWith(packagePrefix) || !name.endsWith(".class")) {
                            continue;
                        }
                        String entryBase = name.substring(packagePrefix.length(), name.length() - ".class".length());
                        if (entryBase.equals(base) || entryBase.startsWith(base + "$")) {
                            matches.add(name);
                        }
                    }
                }
            }
        } catch (IOException | URISyntaxException ex) {
            LOG.warning("Unable to scan " + packageUrl + " for " + base + "$* siblings: " + ex.getMessage());
        }
        return matches;
    }
}