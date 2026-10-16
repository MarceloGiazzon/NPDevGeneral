package com.finalexec.db;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENG-2: the removed {@code RecreateOnAppStart} spelling must never be a wipe. A manifest baked by a
 * pre-STOR-16 generator still carries it in already-assembled apps; the runtime treats it as the
 * normal non-destructive path (and warns), while only the literal {@code Ephemeral} wipes.
 */
class RetiredRecreateOnAppStartManifestTest {

    private static SchemaLifecycleExecutor.SchemaManifest manifestWithStrategy(String strategy) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Server", "jdbc", true, "fp-1", List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id")), Map.of(), Map.of(), Map.of(), Map.of(), false,
                strategy, "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_ALL_DATA_IS_DELETED_ON_EVERY_START", "",
                Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), "NpdevManaged", Map.of(), Map.of(), Map.of());
    }

    @Test
    void theRetiredSpellingIsNeverTreatedAsEphemeral() {
        SchemaLifecycleExecutor.SchemaManifest retired = manifestWithStrategy("RecreateOnAppStart");

        assertFalse(retired.ephemeral(), "the retired spelling must never trigger the wipe");
        assertTrue(retired.retiredRecreateOnAppStart());
    }

    @Test
    void onlyTheLiteralEphemeralStrategyWipes() {
        SchemaLifecycleExecutor.SchemaManifest ephemeral = manifestWithStrategy("Ephemeral");

        assertTrue(ephemeral.ephemeral());
        assertFalse(ephemeral.retiredRecreateOnAppStart());
        assertFalse(manifestWithStrategy("KeepExistingIfCompatible").ephemeral());
    }
}
