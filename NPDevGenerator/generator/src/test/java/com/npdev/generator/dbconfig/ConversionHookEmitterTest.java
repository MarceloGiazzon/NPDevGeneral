package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SER-P7.2 (schema-engine rebuild, Phase 7): {@link ConversionHookEmitter} reads
 * {@code definition/migrations/<ordinal>-<slug>/hook.json} folders, validates each against {@code
 * conversion-hook.schema.json} at GENERATION time, and copies valid ones into the FinalApp staging
 * tree at {@code src/main/resources/db/conversion-hooks/<id>/}.
 */
class ConversionHookEmitterTest {

    @Test
    void noMigrationsDirectoryIsANoOp(@TempDir Path tempDir) throws Exception {
        Path definitionDir = Files.createDirectories(tempDir.resolve("definition"));
        Path modelSourcePath = Files.writeString(definitionDir.resolve("model.json"), "{}");
        Path outRoot = Files.createDirectories(tempDir.resolve("out"));

        new ConversionHookEmitter().emit(modelSourcePath, outRoot);

        assertFalse(Files.exists(outRoot.resolve("src/main/resources/db/conversion-hooks")));
    }

    @Test
    void validHookIsCopiedUnderItsSanitizedId(@TempDir Path tempDir) throws Exception {
        Path definitionDir = Files.createDirectories(tempDir.resolve("definition"));
        Path modelSourcePath = Files.writeString(definitionDir.resolve("model.json"), "{}");
        Path hookDir = Files.createDirectories(definitionDir.resolve("migrations").resolve("001-widen-name"));
        Files.writeString(hookDir.resolve("hook.json"), """
                {
                  "id": "001-widen-name",
                  "claims": ["NARROW_TYPE:widgets:name:VARCHAR(50):VARCHAR(10)"],
                  "description": "widen name back",
                  "verifySql": "SELECT COUNT(*) FROM widgets WHERE name IS NULL",
                  "verifyExpect": 0
                }
                """);
        Files.writeString(hookDir.resolve("convert.sql"), "UPDATE widgets SET name = name;");
        Files.writeString(hookDir.resolve("convert.postgres.sql"), "UPDATE widgets SET name = name; -- pg");
        Path outRoot = Files.createDirectories(tempDir.resolve("out"));

        new ConversionHookEmitter().emit(modelSourcePath, outRoot);

        Path destDir = outRoot.resolve("src/main/resources/db/conversion-hooks/001-widen-name");
        assertTrue(Files.isRegularFile(destDir.resolve("hook.json")), "hook.json should be copied");
        assertTrue(Files.isRegularFile(destDir.resolve("convert.sql")), "convert.sql should be copied");
        assertTrue(Files.isRegularFile(destDir.resolve("convert.postgres.sql")), "engine-variant SQL should be copied");
        assertFalse(Files.isRegularFile(destDir.resolve("convert.h2.sql")), "no H2 variant was authored");
    }

    @Test
    void malformedHookMissingClaimsFailsGenerationNamingTheFileAndRule(@TempDir Path tempDir) throws Exception {
        Path definitionDir = Files.createDirectories(tempDir.resolve("definition"));
        Path modelSourcePath = Files.writeString(definitionDir.resolve("model.json"), "{}");
        Path hookDir = Files.createDirectories(definitionDir.resolve("migrations").resolve("001-bad"));
        Path hookJson = Files.writeString(hookDir.resolve("hook.json"), """
                { "id": "001-bad" }
                """);
        Files.writeString(hookDir.resolve("convert.sql"), "UPDATE widgets SET name = name;");
        Path outRoot = Files.createDirectories(tempDir.resolve("out"));

        ConversionHookEmitter emitter = new ConversionHookEmitter();
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> emitter.emit(modelSourcePath, outRoot));

        assertTrue(exception.getMessage().contains(hookJson.toString()), exception.getMessage());
        assertTrue(exception.getMessage().toLowerCase().contains("claims"), exception.getMessage());
        assertFalse(Files.exists(outRoot.resolve("src/main/resources/db/conversion-hooks")),
                "an invalid hook must not partially land in the output");
    }

    @Test
    void hookMissingConvertSqlFailsGeneration(@TempDir Path tempDir) throws Exception {
        Path definitionDir = Files.createDirectories(tempDir.resolve("definition"));
        Path modelSourcePath = Files.writeString(definitionDir.resolve("model.json"), "{}");
        Path hookDir = Files.createDirectories(definitionDir.resolve("migrations").resolve("001-nosql"));
        Files.writeString(hookDir.resolve("hook.json"), """
                { "id": "001-nosql", "claims": ["DROP_COLUMN:widgets:legacy_flag:BOOLEAN"] }
                """);
        Path outRoot = Files.createDirectories(tempDir.resolve("out"));

        ConversionHookEmitter emitter = new ConversionHookEmitter();
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> emitter.emit(modelSourcePath, outRoot));

        assertTrue(exception.getMessage().contains("convert.sql"), exception.getMessage());
    }

    @Test
    void additionalPropertiesAreRejected(@TempDir Path tempDir) throws Exception {
        Path definitionDir = Files.createDirectories(tempDir.resolve("definition"));
        Path modelSourcePath = Files.writeString(definitionDir.resolve("model.json"), "{}");
        Path hookDir = Files.createDirectories(definitionDir.resolve("migrations").resolve("001-extra"));
        Files.writeString(hookDir.resolve("hook.json"), """
                { "id": "001-extra", "claims": ["DROP_COLUMN:widgets:legacy_flag:BOOLEAN"], "notAllowed": true }
                """);
        Files.writeString(hookDir.resolve("convert.sql"), "UPDATE widgets SET name = name;");
        Path outRoot = Files.createDirectories(tempDir.resolve("out"));

        ConversionHookEmitter emitter = new ConversionHookEmitter();
        assertThrows(IllegalStateException.class, () -> emitter.emit(modelSourcePath, outRoot));
    }

    @Test
    void multipleHooksAreAllCopied(@TempDir Path tempDir) throws Exception {
        Path definitionDir = Files.createDirectories(tempDir.resolve("definition"));
        Path modelSourcePath = Files.writeString(definitionDir.resolve("model.json"), "{}");
        Path first = Files.createDirectories(definitionDir.resolve("migrations").resolve("001-a"));
        Files.writeString(first.resolve("hook.json"), "{ \"id\": \"001-a\", \"claims\": [\"X\"] }");
        Files.writeString(first.resolve("convert.sql"), "SELECT 1;");
        Path second = Files.createDirectories(definitionDir.resolve("migrations").resolve("002-b"));
        Files.writeString(second.resolve("hook.json"), "{ \"id\": \"002-b\", \"claims\": [\"Y\"] }");
        Files.writeString(second.resolve("convert.sql"), "SELECT 2;");
        Path outRoot = Files.createDirectories(tempDir.resolve("out"));

        new ConversionHookEmitter().emit(modelSourcePath, outRoot);

        Path hooksOut = outRoot.resolve("src/main/resources/db/conversion-hooks");
        // try-with-resources, not a bare Files.list(...).count(): the returned Stream holds an open
        // DirectoryStream, and on Windows an open directory handle leaves that directory
        // DELETE-PENDING -- so @TempDir's teardown could not remove any ANCESTOR of it and failed the
        // test in cleanup, long after every assertion had passed. This read as "a Windows file-lock
        // in the harness" and was explained away for long enough to make the local T2 signal
        // non-binary; it is a leaked handle in this line, and POSIX only hides it because it permits
        // unlinking an open directory.
        long hookCount;
        try (var hooks = Files.list(hooksOut)) {
            hookCount = hooks.count();
        }
        assertEquals(2, hookCount);
        assertTrue(Files.isRegularFile(hooksOut.resolve("001-a/hook.json")));
        assertTrue(Files.isRegularFile(hooksOut.resolve("002-b/hook.json")));
    }
}
