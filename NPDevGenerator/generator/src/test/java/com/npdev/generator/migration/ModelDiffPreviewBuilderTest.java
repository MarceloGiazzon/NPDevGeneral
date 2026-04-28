package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDiffPreviewBuilderTest {

    @Test
    void shouldBuildDeterministicPreviewWithAdditiveRiskyAndBreakingChanges() {
        StorageSchemaSnapshot previous = new StorageSchemaSnapshot(
                "baseline",
                List.of(
                        new StorageTableSchema(
                                "patients",
                                List.of(
                                        new StorageColumnSchema("id", "UUID", true, true),
                                        new StorageColumnSchema("first_name", "VARCHAR", true, false),
                                        new StorageColumnSchema("retired_code", "VARCHAR", false, false),
                                        new StorageColumnSchema("status", "VARCHAR", false, false)
                                )
                        )
                )
        );

        StorageSchemaSnapshot current = new StorageSchemaSnapshot(
                "current",
                List.of(
                        new StorageTableSchema(
                                "patients",
                                List.of(
                                        new StorageColumnSchema("id", "UUID", true, true),
                                        new StorageColumnSchema("first_name", "VARCHAR", true, false),
                                        new StorageColumnSchema("status", "INTEGER", true, false),
                                        new StorageColumnSchema("preferred_language", "VARCHAR", false, false)
                                )
                        ),
                        new StorageTableSchema(
                                "insurance_claims",
                                List.of(
                                        new StorageColumnSchema("id", "UUID", true, true),
                                        new StorageColumnSchema("appointment_id", "UUID", true, false)
                                )
                        )
                )
        );

        ModelDiffPreview preview = new ModelDiffPreviewBuilder().build(previous, current);

        assertTrue(preview.deterministicDiff());
        assertEquals("baseline", preview.previousVersion());
        assertEquals("current", preview.currentVersion());
        assertTrue(preview.operationCount() >= 3);
        assertTrue(preview.additiveChanges().stream().anyMatch(value -> value.contains("discover table insurance_claims")));
        assertTrue(preview.additiveChanges().stream().anyMatch(value -> value.contains("patients.preferred_language")));
        assertTrue(preview.riskyChanges().stream().anyMatch(value -> value.contains("tighten required patients.status")));
        assertTrue(preview.riskyChanges().stream().anyMatch(value -> value.contains("change type patients.status from VARCHAR to INTEGER")));
        assertTrue(preview.breakingChanges().stream().anyMatch(value -> value.contains("remove column patients.retired_code")));
    }
}

