package com.npdev.generator.packs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * BUILD-2 (BT-2's own "the linking" follow-on -- ledger item BUILD-2): turns {@link
 * SealedPackBuilder}'s staged Java SOURCE tree into an actual compiled {@code .jar} file --
 * {@link SealedPackBuilder}'s own class doc explicitly stops short of this ("Compiling that source
 * tree into a real jar ... is a build-system operation ... deliberately left to the caller"). This
 * class IS that caller: an in-process {@code javac} compile (the same mechanism {@code
 * SealedPackBuilderTest} already used to get real, diffable {@code .class} bytes for its own proof),
 * followed by packaging the compiled classes plus the {@code META-INF/npdev-pack.properties} manifest
 * into one deterministic jar file.
 *
 * <p><b>Determinism, the same property {@code SealedPackBuilder} already proved at the source/class
 * level.</b> {@link java.util.zip.ZipEntry} embeds a per-entry last-modified timestamp; two builds run
 * seconds apart would otherwise differ byte-for-byte despite compiling identical input. Every entry
 * this class writes gets the SAME fixed timestamp ({@link #DETERMINISTIC_ENTRY_TIME_MILLIS}), and
 * entries are written in a stable, sorted order -- the same two levers {@code :generator:aiToolsJar}'s
 * Gradle {@code Jar} task already relies on ({@code preserveFileTimestamps = false},
 * {@code reproducibleFileOrder = true}) for its own measured byte-identical output.
 *
 * <p><b>An honest, named limitation of the PRODUCTION path (not hidden).</b> Compiling a pack's
 * JPA-annotated entity sources needs {@code jakarta.persistence-api} on the compiling JVM's
 * classpath. Today that dependency is {@code testImplementation}-only in {@code
 * NPDevGenerator/generator/build.gradle} (added for {@code SealedPackBuilderTest}'s own proof) --
 * so this class works correctly when invoked from a test JVM (which is exactly how {@code
 * SealedPackJarBuilderTest} proves it), but invoking it from the generator's own production
 * classpath (e.g. a future {@code npdev pack seal} CLI wrapper's JVM) will fail with a {@link
 * PackJarCompilationException} naming the missing symbol unless that dependency is promoted to
 * {@code implementation} scope first. That build-file change was judged out of scope for this slice
 * (see BUILD-2's ledger item) rather than made silently.
 */
public final class SealedPackJarBuilder {

    /**
     * A fixed instant (not "now") so every independently-built jar for the same pack input is
     * byte-identical. The exact value is arbitrary -- only its stability across builds matters.
     */
    private static final long DETERMINISTIC_ENTRY_TIME_MILLIS =
            LocalDateTime.of(2020, 1, 1, 0, 0, 0).toEpochSecond(ZoneOffset.UTC) * 1000L;

    public record JarResult(PackAbiManifest manifest, Path jarFile) {
    }

    /**
     * Seals {@code packFile} (via {@link SealedPackBuilder#seal}) into a fresh temp source tree,
     * compiles every emitted {@code .java} file with an in-process {@code javac}, and packages the
     * result -- compiled classes plus {@code META-INF/npdev-pack.properties} -- into {@code
     * outputJarFile}. Fully self-contained: staging and classes directories are temp dirs, cleaned up
     * before returning (success or failure) -- the caller only ever sees {@code outputJarFile}.
     *
     * @throws PackNotSealedException        the pack itself is not sealable (see {@link SealedPackBuilder})
     * @throws PackJarCompilationException   javac failed (most commonly a missing compile-time
     *                                       dependency the pack's own entities need -- see this
     *                                       class's own doc)
     */
    public JarResult sealToJar(Path packFile, Path outputJarFile) {
        Path staging = null;
        Path classesDir = null;
        try {
            staging = Files.createTempDirectory("npdev-sealed-pack-src-");
            classesDir = Files.createTempDirectory("npdev-sealed-pack-classes-");

            SealedPackBuilder.SealResult sealResult = new SealedPackBuilder().seal(packFile, staging);

            // Entity sources only, matching SealedPackBuilderTest's own already-shipped precedent:
            // repository interfaces need spring-data-jpa on the compiling classpath, a materially
            // heavier dependency neither that test nor this class adds. This is also the ACTUAL
            // production shape today, not just a scope-narrowing for the proof -- RepositoryEmitter's
            // own emit(CompiledModel) overload has zero callers anywhere in the real app-generation
            // pipeline (GeneratorFacade never calls it), because CRUD for every real generated app
            // runs through the kernel's generic JdbcBusinessConceptStore/GeneratedCrudRuntimeSupport,
            // never a per-concept Spring Data repository. So compiling only entities here matches what
            // a linked app would actually need from this jar: JPA-mapped entity classes for
            // @EntityScan to find, nothing more.
            List<Path> entityJavaFiles = listFilesSorted(staging, ".java").stream()
                    .filter(p -> !p.getFileName().toString().endsWith("Repository.java"))
                    .toList();
            compile(entityJavaFiles, classesDir);

            Path parent = outputJarFile.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writeJar(classesDir, staging, sealResult.manifest(), outputJarFile);

            return new JarResult(sealResult.manifest(), outputJarFile);
        } catch (IOException ioError) {
            throw new UncheckedIOException(ioError);
        } finally {
            deleteQuietly(staging);
            deleteQuietly(classesDir);
        }
    }

    private static void compile(List<Path> javaFiles, Path outDir) {
        if (javaFiles.isEmpty()) {
            return;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "No system Java compiler available (need a JDK, not a JRE) to seal a pack into a jar");
        }
        String classpath = System.getProperty("java.class.path");
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(List.of(
                "-d", outDir.toString(),
                "-classpath", classpath,
                "-proc:none"
        ));
        for (Path file : javaFiles) {
            args.add(file.toString());
        }
        int exit = compiler.run(null, new PrintStream(diagnostics), new PrintStream(diagnostics),
                args.toArray(new String[0]));
        if (exit != 0) {
            throw new PackJarCompilationException(
                    "javac failed sealing pack sources into classes (exit " + exit + "):\n"
                            + diagnostics.toString(StandardCharsets.UTF_8));
        }
    }

    private static void writeJar(
            Path classesDir, Path sourceStaging, PackAbiManifest manifest, Path outputJarFile
    ) throws IOException {
        Manifest jarManifest = new Manifest();
        jarManifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        jarManifest.getMainAttributes().putValue("Npdev-Pack-Id", manifest.packId());
        jarManifest.getMainAttributes().putValue("Npdev-Pack-Version", manifest.packVersion());
        jarManifest.getMainAttributes().putValue("Npdev-Pack-Major-Version", Integer.toString(manifest.packMajorVersion()));
        jarManifest.getMainAttributes().putValue("Npdev-Kernel-Abi-Version", manifest.kernelAbiVersion());
        ByteArrayOutputStream manifestBytes = new ByteArrayOutputStream();
        jarManifest.write(manifestBytes);

        List<Path> classFiles = listFilesSorted(classesDir, ".class");
        Path packMetaProperties = sourceStaging.resolve("META-INF").resolve("npdev-pack.properties");

        // Deliberately the no-manifest JarOutputStream(OutputStream) constructor, not
        // JarOutputStream(OutputStream, Manifest): the latter writes MANIFEST.MF as its own entry with
        // a timestamp this class cannot control, which would reintroduce exactly the nondeterminism
        // every OTHER entry here is written to avoid.
        try (OutputStream fileOut = Files.newOutputStream(outputJarFile);
             JarOutputStream jarOut = new JarOutputStream(fileOut)) {
            writeEntry(jarOut, "META-INF/MANIFEST.MF", manifestBytes.toByteArray());
            if (Files.isRegularFile(packMetaProperties)) {
                writeEntry(jarOut, "META-INF/npdev-pack.properties", Files.readAllBytes(packMetaProperties));
            }
            for (Path classFile : classFiles) {
                String relative = classesDir.relativize(classFile).toString().replace('\\', '/');
                writeEntry(jarOut, relative, Files.readAllBytes(classFile));
            }
        }
    }

    private static void writeEntry(JarOutputStream jarOut, String name, byte[] bytes) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(DETERMINISTIC_ENTRY_TIME_MILLIS);
        jarOut.putNextEntry(entry);
        jarOut.write(bytes);
        jarOut.closeEntry();
    }

    private static List<Path> listFilesSorted(Path root, String suffix) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(suffix))
                    .sorted(java.util.Comparator.comparing(p -> root.relativize(p).toString().replace('\\', '/')))
                    .toList();
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup of the staging/classes directory
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup of the staging/classes directory
        }
    }
}
