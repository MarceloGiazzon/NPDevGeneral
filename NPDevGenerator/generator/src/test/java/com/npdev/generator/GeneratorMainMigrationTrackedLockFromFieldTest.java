package com.npdev.generator;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.npdev.dsl.v1.pack.PackLockFile;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PK-5 regression (adversarial multi-agent review of PR #70, finding #1, high severity):
 * {@link GeneratorMain#advanceMigrationTrackedLock} used to rebuild a migration-tracked packId's
 * {@code npdev.lock} entry via {@code PackLockFile.LockedPack}'s 4-arg backward-compat constructor,
 * which hardcodes {@code from=""} -- silently zeroing a from-based remote pack's coordinate on
 * every generate that advances its {@code migratedVersion}. The very next generate/validate then
 * fails {@code PackDependencyGraphWalker.resolveRemotePackFile}'s DENIED-path lock lookup ("is not
 * in npdev.lock -- run 'npdev pack add' first") even though {@code pack add} really was run and the
 * cache entry is still intact -- breaking PK-5's own core promise (fetch once, then generate/
 * validate offline forever) for any from-based pack that also declares a migration chain.
 *
 * <p>Exercises {@code advanceMigrationTrackedLock} directly (package-private, same convention as
 * {@link GeneratorMainMigrationDisabledTest}'s {@code rejectUnsupportedMigrationManagement} calls)
 * rather than a full CLI generate, since the bug is entirely in this one lock-merge step and a full
 * generate needs a real compiled model, templates, and output tree to exercise nothing extra.
 */
class GeneratorMainMigrationTrackedLockFromFieldTest {

    @TempDir
    Path appRoot;

    private static final String FROM_COORDINATE = "git+file:///scratch/identity-repo@v3.0.0";

    @Test
    void fromSurvivesWhenMergingWithAnExistingLockEntry() throws Exception {
        // Simulates the state right after a real "npdev pack add" for a from-based pack: npdev.lock
        // already has a real entry, `from` populated, migratedVersion not yet advanced past "1.0.0".
        PackLockFile.of(Map.of(
                "identity", new PackLockFile.LockedPack(
                        "3.0.0", "sha256:" + "a".repeat(64), "/cache/sha256/aaaa/pack.json",
                        "1.0.0", FROM_COORDINATE)
        )).write(appRoot);

        // What a live generate's resolution would compute as "fresh" for this packId (matches
        // PackDependencyGraphWalker.toLockEntries' own shape -- migratedVersion "" until advanced).
        PackLockFile.LockedPack fresh = new PackLockFile.LockedPack(
                "3.0.0", "sha256:" + "a".repeat(64), "/cache/sha256/aaaa/pack.json", "", FROM_COORDINATE);
        ResolvedModelSource resolvedModelSource = fixture(Map.of("identity", fresh));

        GeneratorMain.advanceMigrationTrackedLock(resolvedModelSource);

        PackLockFile.LockedPack rewritten = PackLockFile.read(appRoot).packs().get("identity");
        assertEquals(FROM_COORDINATE, rewritten.from(),
                "the from coordinate must survive advanceMigrationTrackedLock's lock rewrite, not be zeroed");
        assertEquals("3.0.0", rewritten.migratedVersion(), "migratedVersion must still advance as before");
        assertEquals("3.0.0", rewritten.resolvedVersion());
        assertEquals("/cache/sha256/aaaa/pack.json", rewritten.sourcePath());
    }

    @Test
    void fromSurvivesWhenNoExistingLockEntryExists() throws Exception {
        // The other branch: a packId with no prior npdev.lock entry at all (the method's own doc
        // covers this as the "common case" for a direct import with no transitive dependency).
        PackLockFile.LockedPack fresh = new PackLockFile.LockedPack(
                "2.0.0", "sha256:" + "b".repeat(64), "/cache/sha256/bbbb/pack.json", "", FROM_COORDINATE);
        ResolvedModelSource resolvedModelSource = fixture(Map.of("billing", fresh));

        GeneratorMain.advanceMigrationTrackedLock(resolvedModelSource);

        PackLockFile.LockedPack written = PackLockFile.read(appRoot).packs().get("billing");
        assertEquals(FROM_COORDINATE, written.from());
        assertEquals("2.0.0", written.migratedVersion());
    }

    @Test
    void localPackWithNoFromFieldIsUnaffected() throws Exception {
        // Regression guard the other direction: a plain local ($ref) pack's from stays "" as before
        // -- this fix must not start inventing a from value where none exists.
        PackLockFile.LockedPack fresh = new PackLockFile.LockedPack(
                "2.0.0", "sha256:" + "c".repeat(64), "packs/billing/pack.json", "");
        ResolvedModelSource resolvedModelSource = fixture(Map.of("billing", fresh));

        GeneratorMain.advanceMigrationTrackedLock(resolvedModelSource);

        PackLockFile.LockedPack written = PackLockFile.read(appRoot).packs().get("billing");
        assertEquals("", written.from());
    }

    private ResolvedModelSource fixture(Map<String, PackLockFile.LockedPack> migrationTrackedPacks) {
        return new ResolvedModelSource(
                appRoot.resolve("model.json"),
                appRoot,
                JsonNodeFactory.instance.objectNode(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                Map.of(),
                migrationTrackedPacks
        );
    }
}
