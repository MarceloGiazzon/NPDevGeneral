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
 * STOR-16: the {@code Ephemeral} strategy at generation time.
 *
 * <p>Measured RED for the whole item: {@code SchemaLifecycleExecutor} read {@code strategy} at
 * exactly one line, comparing it to {@code "DropAndRecreateOnStructureChange"}, so
 * {@code RecreateOnAppStart} had no code path at all and there was no posture meaning "this data is
 * disposable". These cover the generation half -- the declaration, the alias, and the two refusals.
 */
class SchemaLifecycleEphemeralTest {

    private static Path writeDefinition(Path directory, String json) throws Exception {
        Path path = directory.resolve("db.definition.json");
        Files.writeString(path, json);
        return path;
    }

    // -- the strategy itself ---------------------------------------------------------------------

    @Test
    void ephemeralParsesAndRoundTripsUnderItsOwnName() {
        SchemaLifecycleStrategy strategy = SchemaLifecycleStrategy.parse("Ephemeral");

        assertEquals(SchemaLifecycleStrategy.EPHEMERAL, strategy);
        assertEquals("Ephemeral", strategy.externalName());
    }

    @Test
    void theRetiredSpellingIsAcceptedAsAnAliasAndReportedAsDeprecated() {
        // Safe by measurement, not assumption: all 7 corpus definitions using the old name are
        // InMemory, where "drop and recreate on boot" and "memory is empty on boot" are the same
        // statement.
        assertEquals(SchemaLifecycleStrategy.EPHEMERAL,
                SchemaLifecycleStrategy.parse("RecreateOnAppStart"));
        assertTrue(SchemaLifecycleStrategy.isDeprecatedSpelling("RecreateOnAppStart"));
        assertTrue(SchemaLifecycleStrategy.isDeprecatedSpelling("  recreateonappstart  "),
                "parse is case- and whitespace-insensitive, so the deprecation probe must be too");
        assertFalse(SchemaLifecycleStrategy.isDeprecatedSpelling("Ephemeral"));
        assertTrue(SchemaLifecycleStrategy.deprecationWarning().contains("Ephemeral"),
                "the warning has to name the replacement, or it is just a complaint");
        assertTrue(SchemaLifecycleStrategy.deprecationWarning().contains("npdev migrate db-lifecycle"),
                "and it has to name the codemod that fixes it");
    }

    @Test
    void anAliasedDefinitionIsEmittedUnderTheNewNameNotTheOld() throws Exception {
        // externalName() is the only serialization path, so an alias resolves once at parse time and
        // every downstream artifact -- manifest, application.properties -- says "Ephemeral".
        assertEquals("Ephemeral", SchemaLifecycleStrategy.parse("RecreateOnAppStart").externalName());
    }

    // -- confirmation ----------------------------------------------------------------------------

    @Test
    void aPhysicalEngineNeedsTheEphemeralConfirmationAndTableScope() {
        SchemaLifecyclePolicy declared = new SchemaLifecyclePolicy(
                SchemaLifecycleStrategy.EPHEMERAL, true,
                SchemaLifecyclePolicy.EPHEMERAL_CONFIRMATION,
                SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE);

        assertTrue(declared.ephemeralConfirmedFor(DatabaseEngine.H2_LOCAL));
    }

    @Test
    void theTableDataConfirmationDoesNotAuthoriseEphemeral() {
        // I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED says "this particular change deletes data". An
        // author who typed it once for a column drop has not agreed that every future boot starts
        // from empty, and reusing the token would put those words in their mouth.
        SchemaLifecyclePolicy wrongToken = new SchemaLifecyclePolicy(
                SchemaLifecycleStrategy.EPHEMERAL, true,
                SchemaLifecyclePolicy.TABLE_DATA_CONFIRMATION,
                SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE);

        assertFalse(wrongToken.ephemeralConfirmedFor(DatabaseEngine.H2_LOCAL));
    }

    @Test
    void inMemoryAlsoAcceptsThePairItAlreadyCarries() {
        // This is what lets the 7 corpus definitions migrate by renaming ONE string, which is the
        // entire reason re-pointing the retired name was judged safe.
        SchemaLifecyclePolicy legacy = new SchemaLifecyclePolicy(
                SchemaLifecycleStrategy.EPHEMERAL, true,
                SchemaLifecyclePolicy.IN_MEMORY_CONFIRMATION,
                SchemaLifecyclePolicy.NPDEV_STORE_SCOPE);

        assertTrue(legacy.ephemeralConfirmedFor(DatabaseEngine.IN_MEMORY));
        assertFalse(legacy.ephemeralConfirmedFor(DatabaseEngine.H2_LOCAL),
                "a physical engine must not inherit InMemory's confirmation");
    }

    @Test
    void allowDestructiveRecreateFalseIsNeverConfirmed() {
        SchemaLifecyclePolicy notAllowed = new SchemaLifecyclePolicy(
                SchemaLifecycleStrategy.EPHEMERAL, false,
                SchemaLifecyclePolicy.EPHEMERAL_CONFIRMATION,
                SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE);

        assertFalse(notAllowed.ephemeralConfirmedFor(DatabaseEngine.H2_LOCAL));
    }

    // -- generation-time refusals ----------------------------------------------------------------

    @Test
    void anUndeclaredEphemeralDefinitionIsRefusedAtGenerationTime(@TempDir Path tempDir) throws Exception {
        Path definitionPath = writeDefinition(tempDir, """
                {
                  "database": { "engine": "H2Local", "username": "test", "password": "test",
                                 "createInternalTables": true, "createBusinessTables": true },
                  "schemaLifecycle": { "strategy": "Ephemeral", "allowDestructiveRecreate": true,
                                        "destructiveRecreateConfirmation": "", "scope": "NpdevOwnedTablesOnly" }
                }
                """);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new UserDatabaseDefinitionLoader().load(definitionPath, null));
        assertTrue(exception.getMessage().contains("Ephemeral"), exception.getMessage());
        assertTrue(exception.getMessage().contains(SchemaLifecyclePolicy.EPHEMERAL_CONFIRMATION),
                "the message must quote the exact string to add: " + exception.getMessage());
    }

    @Test
    void aProperlyDeclaredEphemeralDefinitionLoads(@TempDir Path tempDir) throws Exception {
        Path definitionPath = writeDefinition(tempDir, """
                {
                  "database": { "engine": "H2Local", "username": "test", "password": "test",
                                 "createInternalTables": true, "createBusinessTables": true },
                  "schemaLifecycle": { "strategy": "Ephemeral", "allowDestructiveRecreate": true,
                                        "destructiveRecreateConfirmation": "I_UNDERSTAND_ALL_DATA_IS_DELETED_ON_EVERY_START",
                                        "scope": "NpdevOwnedTablesOnly" }
                }
                """);

        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definitionPath, null);

        assertEquals(SchemaLifecycleStrategy.EPHEMERAL, plan.schemaLifecycle().strategy());
    }

    @Test
    void externallyManagedPlusEphemeralIsRefusedAndBlamesOwnership(@TempDir Path tempDir) throws Exception {
        // The ordering matters as much as the refusal. Told first that it needs a destructive
        // confirmation, an author would add the very token an ExternallyManaged database forbids,
        // and only then be refused for the real reason.
        Path definitionPath = writeDefinition(tempDir, """
                {
                  "database": { "engine": "H2Local", "username": "test", "password": "test",
                                 "createInternalTables": true, "createBusinessTables": true },
                  "schemaLifecycle": { "strategy": "Ephemeral", "allowDestructiveRecreate": false,
                                        "destructiveRecreateConfirmation": "", "scope": "NpdevOwnedTablesOnly",
                                        "ownership": "ExternallyManaged" }
                }
                """);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new UserDatabaseDefinitionLoader().load(definitionPath, null));
        assertTrue(exception.getMessage().contains("ExternallyManaged"), exception.getMessage());
        assertFalse(exception.getMessage().contains(SchemaLifecyclePolicy.EPHEMERAL_CONFIRMATION),
                "must not tell an ExternallyManaged app to add a destructive confirmation: "
                        + exception.getMessage());
    }
}
