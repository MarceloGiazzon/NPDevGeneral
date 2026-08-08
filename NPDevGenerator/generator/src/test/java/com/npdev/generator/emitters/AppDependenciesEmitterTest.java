package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROUND2_PLAN.md R1a: regression cover for the dependency emitter -- deps-and-java/PLAN.md's P2
 * (javaVersion) got {@code GeneratorMainJavaVersionResolutionTest} at ship time; this emitter (P3)
 * shipped with none, making it the least-protected code an external user actually depends on.
 */
final class AppDependenciesEmitterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path finalAppRoot;

    @Test
    void mavenCoordinateShorthandBecomesAnImplementationLine() throws Exception {
        JsonNode config = MAPPER.readTree("""
                { "build": { "dependencies": [ "com.google.guava:guava:33.0.0-jre" ] } }
                """);

        AppDependenciesEmitter.EmitResult result = new AppDependenciesEmitter()
                .emit(config, finalAppRoot, null);

        assertTrue(result.wroteFile());
        String contents = Files.readString(finalAppRoot.resolve(AppDependenciesEmitter.RELATIVE_PATH));
        assertTrue(contents.contains("implementation 'com.google.guava:guava:33.0.0-jre'"), contents);
    }

    @Test
    void objectFormHonoursItsDeclaredScope() throws Exception {
        JsonNode config = MAPPER.readTree("""
                { "build": { "dependencies": [
                    { "coordinate": "org.apache.commons:commons-lang3:3.14.0", "scope": "testImplementation" }
                ] } }
                """);

        AppDependenciesEmitter.EmitResult result = new AppDependenciesEmitter()
                .emit(config, finalAppRoot, null);

        assertTrue(result.wroteFile());
        String contents = Files.readString(finalAppRoot.resolve(AppDependenciesEmitter.RELATIVE_PATH));
        assertTrue(contents.contains("testImplementation 'org.apache.commons:commons-lang3:3.14.0'"), contents);
    }

    @Test
    void extraRepositoryIsEmittedIntoTheGeneratedFile() throws Exception {
        JsonNode config = MAPPER.readTree("""
                { "build": { "repositories": [ { "url": "https://packages.example.com/maven" } ] } }
                """);

        AppDependenciesEmitter.EmitResult result = new AppDependenciesEmitter()
                .emit(config, finalAppRoot, null);

        assertTrue(result.wroteFile());
        String contents = Files.readString(finalAppRoot.resolve(AppDependenciesEmitter.RELATIVE_PATH));
        assertTrue(contents.contains("maven { url 'https://packages.example.com/maven' }"), contents);
        assertFalse(contents.contains("dependencies {"), contents);
    }

    @Test
    void noBuildBlockEmitsNothingAndDoesNotCrash() throws Exception {
        AppDependenciesEmitter.EmitResult result = new AppDependenciesEmitter()
                .emit(MAPPER.readTree("{}"), finalAppRoot, null);

        assertFalse(result.wroteFile());
        assertTrue(result.collisionWarnings().isEmpty());
        assertFalse(Files.exists(finalAppRoot.resolve(AppDependenciesEmitter.RELATIVE_PATH)));
    }

    @Test
    void nullConfigEmitsNothingAndDoesNotCrash() throws Exception {
        AppDependenciesEmitter.EmitResult result = new AppDependenciesEmitter()
                .emit(null, finalAppRoot, null);

        assertFalse(result.wroteFile());
        assertFalse(Files.exists(finalAppRoot.resolve(AppDependenciesEmitter.RELATIVE_PATH)));
    }

    @Test
    void localJarIsCopiedIntoAppLibsWithItsFileNamePreserved(@TempDir Path definitionRoot) throws Exception {
        Path libsDir = definitionRoot.resolve("libs");
        Files.createDirectories(libsDir);
        Path jar = libsDir.resolve("acme-widgets-1.2.jar");
        Files.writeString(jar, "not a real jar, just bytes for the copy check");
        Path modelPath = definitionRoot.resolve("model.json");
        Files.writeString(modelPath, "{}");

        List<String> copied = new AppDependenciesEmitter().copyLocalJars(modelPath, finalAppRoot);

        assertEquals(List.of("acme-widgets-1.2.jar"), copied);
        Path destination = finalAppRoot.resolve("npdev-app-libs").resolve("acme-widgets-1.2.jar");
        assertTrue(Files.exists(destination));
        assertEquals(Files.readString(jar), Files.readString(destination));
    }

    @Test
    void coordinateCollidingWithAnExistingPlatformDependencyReturnsAWarning(@TempDir Path assembledRoot) throws Exception {
        Path assembledBuildGradle = assembledRoot.resolve("build.gradle");
        Files.writeString(assembledBuildGradle, """
                dependencies {
                    implementation 'com.google.guava:guava:32.0.0-jre'
                }
                """);
        JsonNode config = MAPPER.readTree("""
                { "build": { "dependencies": [ "com.google.guava:guava:33.0.0-jre" ] } }
                """);

        AppDependenciesEmitter.EmitResult result = new AppDependenciesEmitter()
                .emit(config, finalAppRoot, assembledBuildGradle);

        assertTrue(result.wroteFile());
        assertEquals(1, result.collisionWarnings().size());
        assertTrue(result.collisionWarnings().get(0).contains("com.google.guava:guava"), result.collisionWarnings().get(0));
        String contents = Files.readString(finalAppRoot.resolve(AppDependenciesEmitter.RELATIVE_PATH));
        assertTrue(contents.contains("WARNING: "), contents);
    }
}
