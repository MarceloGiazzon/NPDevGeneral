package com.finalexec.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SER final-closure-plan G1 (step 1): {@link ConversionHookRunner} loads hooks via {@code
 * PathMatchingResourcePatternResolver.getResources("classpath*:db/conversion-hooks/*&#47;hook.json")}
 * then resolves each hook's sibling {@code convert.sql} via {@code Resource.createRelative(...)}
 * (SER-P7.3). Every existing test drives this off a plain DIRECTORY classpath entry
 * ({@code build/resources/test/db/conversion-hooks/...}); inside a real packaged boot jar those
 * resources live under a {@code jar:file:...!/BOOT-INF/classes/...} URL, and {@code createRelative}
 * resolving siblings correctly there was UNVERIFIED. If it silently mis-resolved, every hook would load
 * with a {@code null} convert SQL, and {@link ConversionHookRunner#run} would refuse every boot that has
 * an unresolved diff -- a production-breaking failure mode no directory-classpath test could see.
 *
 * <p>This test builds a real {@code .jar} at runtime containing exactly one hook, loads it through a
 * {@link URLClassLoader} whose PARENT is the platform classloader only (deliberately NOT the test's own
 * application classloader) -- so {@code classpath*:} resolves ONLY this jar's hook, not also the dozen
 * real fixture hooks under {@code src/test/resources/db/conversion-hooks/} that every other test in this
 * package already uses. That gives a precise, unambiguous assertion (exactly one hook, exactly this
 * claim, exactly this convert SQL resolved) instead of a merely non-zero count that those fixtures would
 * satisfy regardless of whether jar resolution actually worked.
 */
class ConversionHookJarLoadingTest {

    @Test
    void hookAndItsSiblingConvertSqlResolveFromInsideARealJar() throws Exception {
        // Not @TempDir: on Windows, JarURLConnection caches opened JarFile handles JVM-wide, and
        // neither URLClassLoader.close() nor setDefaultUseCaches(false) (tried first; did not help --
        // Spring's own resource resolution opens jar: connections that keep the cache alive
        // regardless) reliably releases the handle before JUnit's @TempDir extension tries to delete
        // it, failing the test on a cleanup IOException even though every assertion below passes.
        // Manage the temp dir manually with a best-effort, retrying delete instead (same class of
        // problem, same shape of fix, as FinalAppAssembler.deletePathWithRetry elsewhere in this repo).
        Path tempDir = Files.createTempDirectory("conversion-hook-jar-test-");
        try {
            Path jarFile = tempDir.resolve("conversion-hooks-test.jar");
            writeHookJar(jarFile);

            ClassLoader originalContextLoader = Thread.currentThread().getContextClassLoader();
            try (URLClassLoader jarLoader = new URLClassLoader(
                    new URL[] {jarFile.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                Thread.currentThread().setContextClassLoader(jarLoader);

                Map<String, String> claims = ConversionHookRunner.loadClaimIndex();
                assertEquals(Map.of("DROP_COLUMN:jartest_table:jartest_column:INTEGER", "jartest-hook"), claims,
                        "the jar's hook.json claim must be found via classpath*: scanning inside a jar: URL");

                long withConvertSql = ConversionHookRunner.loadedHooksWithConvertSqlCount();
                assertEquals(1L, withConvertSql,
                        "the sibling convert.sql must resolve via Resource.createRelative(...) from a jar: URL, "
                                + "not just the hook.json itself");
            } finally {
                Thread.currentThread().setContextClassLoader(originalContextLoader);
            }
        } finally {
            deleteRecursivelyBestEffort(tempDir);
        }
    }

    /** Best-effort, retrying recursive delete -- never throws. See the javadoc on the caller for why
     *  this test cannot rely on JUnit's {@code @TempDir} cleanup on Windows. */
    private static void deleteRecursivelyBestEffort(Path root) {
        for (int attempt = 1; attempt <= 5; attempt++) {
            try (var stream = Files.walk(root)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // retried below
                    }
                });
                if (!Files.exists(root)) {
                    return;
                }
            } catch (IOException ignored) {
                // retried below
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.out.println("NPDev test cleanup: could not fully delete " + root
                + " (Windows file-lock, non-fatal -- OS temp cleanup will reclaim it eventually).");
    }

    /** A minimal real jar: {@code db/conversion-hooks/jartest-hook/hook.json} + sibling
     *  {@code convert.sql}, nothing else -- so {@code classpath*:db/conversion-hooks/*&#47;hook.json}
     *  against a classloader scoped to ONLY this jar finds exactly one hook. */
    private static void writeHookJar(Path jarFile) throws IOException {
        String hookJson = """
                {
                  "id": "jartest-hook",
                  "claims": ["DROP_COLUMN:jartest_table:jartest_column:INTEGER"],
                  "description": "SER closure-plan G1: proves sibling resolution works inside a packaged jar."
                }
                """;
        String convertSql = "SELECT 1;\n";

        try (JarOutputStream jar = new JarOutputStream(java.nio.file.Files.newOutputStream(jarFile))) {
            jar.putNextEntry(new JarEntry("db/"));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("db/conversion-hooks/"));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("db/conversion-hooks/jartest-hook/"));
            jar.closeEntry();

            jar.putNextEntry(new JarEntry("db/conversion-hooks/jartest-hook/hook.json"));
            jar.write(hookJson.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();

            jar.putNextEntry(new JarEntry("db/conversion-hooks/jartest-hook/convert.sql"));
            jar.write(convertSql.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        assertTrue(java.nio.file.Files.isRegularFile(jarFile), "the test jar must have been written");
    }
}
