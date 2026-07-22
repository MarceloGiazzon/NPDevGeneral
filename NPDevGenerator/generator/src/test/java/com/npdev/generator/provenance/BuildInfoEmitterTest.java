package com.npdev.generator.provenance;

import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildInfoEmitterTest {

    @TempDir
    Path tempDir;

    @Test
    void emitsRealModelVersionAndGeneratorVersionFromGitDescribe() throws Exception {
        CompiledModel model = new CompiledModel("trial.widgets", "1.0.0", "2.3", Map.of());

        new BuildInfoEmitter().emit(model, tempDir);

        Path target = tempDir.resolve(BuildInfoEmitter.RELATIVE_PATH);
        assertTrue(Files.exists(target));
        Properties properties = new Properties();
        try (var in = Files.newInputStream(target)) {
            properties.load(in);
        }
        assertEquals("2.3", properties.getProperty("npdev.version"));
        assertEquals("trial.widgets", properties.getProperty("npdev.namespace"));
        // LNCH-21: the generator version now tracks `git describe --tags --always` against the
        // real platform release tags (docs/RELEASE_PROCESS.md) rather than a hardcoded literal --
        // this test runs from inside the actual NPDev git checkout (which has real tags: beta0,
        // beta1, ...), so it must resolve to something real, not the "no git context" fallback.
        String generatorVersion = properties.getProperty("npdev.generator.version");
        assertFalse(generatorVersion == null || generatorVersion.isBlank());
        assertFalse("UNKNOWN".equals(generatorVersion), "expected a real git-describe value from within a real checkout, not the no-git fallback");
        assertFalse(properties.getProperty("npdev.builtAt").isBlank());
        assertEquals(properties.getProperty("npdev.builtAt"), properties.getProperty("npdev.generator.generatedAtUtc"));
    }

    @Test
    void fallsBackToUnknownVersionWhenModelHasNone() throws Exception {
        new BuildInfoEmitter().emit(null, tempDir);

        Properties properties = new Properties();
        try (var in = Files.newInputStream(tempDir.resolve(BuildInfoEmitter.RELATIVE_PATH))) {
            properties.load(in);
        }
        assertEquals("UNKNOWN", properties.getProperty("npdev.version"));
    }
}
