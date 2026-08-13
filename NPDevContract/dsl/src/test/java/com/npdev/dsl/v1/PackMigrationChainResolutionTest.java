package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.pack.PackLockFile;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PK-4 Stage C/D: real end-to-end proof through {@link ModelSourceResolver#resolve}, the actual
 * generate-time entry point -- not just the pure {@code PackMigrationComposer}/{@code
 * PackMigrationChainSynthesizer} unit tests, which never touch {@code npdev.lock} or the merge
 * pipeline at all. Covers the card's own headline scenario: a direct app import (no transitive
 * dependency needed) whose pack jumps multiple versions in one generate must still replay every
 * skipped hop's rename.
 */
class PackMigrationChainResolutionTest {

    @TempDir
    Path temp;

    private static final String IDENTITY_V3_WITH_CHAIN = """
            {
              "dslVersion": "1.0.0",
              "pack": "identity",
              "version": "3.0.0",
              "migrations": {
                "1.0.0 -> 2.0.0": [
                  { "op": "renameField", "concept": "User", "from": "name", "to": "displayName" }
                ],
                "2.0.0 -> 3.0.0": [
                  { "op": "addField", "concept": "User", "field": "notes" }
                ]
              },
              "concepts": [
                { "name": "User", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "displayName", "type": "string" },
                  { "name": "notes", "type": "string" }
                ] }
              ]
            }
            """;

    private static final String MODEL_IMPORTING_IDENTITY = """
            {
              "namespace": "migration.test",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "packs": [ { "$ref": "packs/identity/pack.json" } ]
            }
            """;

    @Test
    void multiHopSkipSynthesizesRenamedFromOnADirectAppImport() throws Exception {
        write("packs/identity/pack.json", IDENTITY_V3_WITH_CHAIN);
        Path model = write("model.json", MODEL_IMPORTING_IDENTITY);
        writeLockWithMigratedVersion("identity", "packs/identity/pack.json", "1.0.0");

        ResolvedModelSource source = new ModelSourceResolver().resolve(model);

        JsonNode displayName = fieldNamed(userConcept(source), "displayName");
        assertEquals("name", displayName.get("renamedFrom").asText(),
                "identity@1.0 -> @3.0, skipping @2.0, must still replay @2.0's rename");
        JsonNode notes = fieldNamed(userConcept(source), "notes");
        assertNull(notes.get("renamedFrom"), "addField must never produce a renamedFrom marker");
    }

    @Test
    void firstEverGenerateWithNoLockStillSynthesizesFromTheChainsEarliestVersion() throws Exception {
        // No npdev.lock written at all -- "untracked" must NOT be read as "already current", because
        // an untracked live database could be a genuinely pre-existing install still sitting at the
        // pack's ORIGINAL version (this is the exact failure this card exists to prevent: silently
        // skipping a real rename because there was no recorded baseline). The only version an
        // untracked database could possibly be at is the chain's own earliest declared version, so
        // that is what must be composed from -- harmlessly, if this really is a fresh install with
        // nothing live yet (see PackMigrationChain.earliestFromVersion's own doc for why).
        write("packs/identity/pack.json", IDENTITY_V3_WITH_CHAIN);
        Path model = write("model.json", MODEL_IMPORTING_IDENTITY);

        ResolvedModelSource source = new ModelSourceResolver().resolve(model);

        JsonNode displayName = fieldNamed(userConcept(source), "displayName");
        assertEquals("name", displayName.get("renamedFrom").asText(),
                "an untracked pack with a real chain must compose from the chain's earliest version, not no-op");
    }

    @Test
    void missingHopInTheChainRefusesRatherThanSilentlyDropping() throws Exception {
        write("packs/identity/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "identity",
                  "version": "3.0.0",
                  "migrations": {
                    "2.0.0 -> 3.0.0": [ { "op": "addField", "concept": "User", "field": "notes" } ]
                  },
                  "concepts": [
                    { "name": "User", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "displayName", "type": "string" }
                    ] }
                  ]
                }
                """);
        Path model = write("model.json", MODEL_IMPORTING_IDENTITY);
        writeLockWithMigratedVersion("identity", "packs/identity/pack.json", "1.0.0");

        IOException e = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(e.getMessage().contains("no migration chain entry starts at version 1.0.0"), e.getMessage());
    }

    @Test
    void redControl_deletingTheChainEntryTurnsAPreviouslyWorkingUpgradeIntoARefusal() throws Exception {
        // Same fixture as the success case, but with the 1.0.0 -> 2.0.0 hop removed -- the exact RED
        // control the card's own proof section demands.
        write("packs/identity/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "identity",
                  "version": "3.0.0",
                  "migrations": {
                    "2.0.0 -> 3.0.0": [ { "op": "addField", "concept": "User", "field": "notes" } ]
                  },
                  "concepts": [
                    { "name": "User", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "displayName", "type": "string" },
                      { "name": "notes", "type": "string" }
                    ] }
                  ]
                }
                """);
        Path model = write("model.json", MODEL_IMPORTING_IDENTITY);
        writeLockWithMigratedVersion("identity", "packs/identity/pack.json", "1.0.0");

        assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
    }

    @Test
    void aPackWithNoMigrationsObjectAtAllIsCompletelyUntouched() throws Exception {
        write("packs/identity/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "identity",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "User", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "name", "type": "string" }
                    ] }
                  ]
                }
                """);
        Path model = write("model.json", MODEL_IMPORTING_IDENTITY);
        // No lock, no migrations -- matches every real in-repo pack today.

        ResolvedModelSource source = new ModelSourceResolver().resolve(model);

        JsonNode nameField = fieldNamed(userConcept(source), "name");
        assertNull(nameField.get("renamedFrom"));
    }

    @Test
    void sameVersionRegenerateIsANoOpEvenWithAChainPresent() throws Exception {
        write("packs/identity/pack.json", IDENTITY_V3_WITH_CHAIN);
        Path model = write("model.json", MODEL_IMPORTING_IDENTITY);
        // Already migrated all the way to 3.0.0 -- regenerating at the same version must compose
        // an empty range, not re-apply anything.
        writeLockWithMigratedVersion("identity", "packs/identity/pack.json", "3.0.0");

        ResolvedModelSource source = new ModelSourceResolver().resolve(model);

        JsonNode displayName = fieldNamed(userConcept(source), "displayName");
        assertNull(displayName.get("renamedFrom"));
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private JsonNode userConcept(ResolvedModelSource source) {
        for (JsonNode concept : source.resolvedRoot().get("concepts")) {
            if ("identity::User".equals(concept.get("name").asText())) {
                return concept;
            }
        }
        throw new AssertionError("identity::User concept not found in resolved model: " + source.resolvedRoot());
    }

    private JsonNode fieldNamed(JsonNode concept, String name) {
        for (JsonNode field : concept.get("fields")) {
            if (name.equals(field.get("name").asText())) {
                return field;
            }
        }
        throw new AssertionError("field '" + name + "' not found on concept: " + concept);
    }

    private void writeLockWithMigratedVersion(String packId, String relativeSourcePath, String migratedVersion) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path file = temp.resolve(relativeSourcePath);
        String resolvedVersion = mapper.readTree(file.toFile()).get("version").asText();
        Map<String, PackLockFile.LockedPack> packs = new LinkedHashMap<>();
        packs.put(packId, new PackLockFile.LockedPack(
                resolvedVersion, PackLockFile.sha256(file), relativeSourcePath, migratedVersion));
        PackLockFile.of(packs).write(temp);
    }

    private Path write(String relative, String content) throws Exception {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
