package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationRiskAssessmentBuilderTest {

    @Test
    void shouldClassifySafeBackfillManualAndBreakingChanges() {
        // add column = safe, drop column = dangerous, rename column = dangerous, change type = dangerous
        // migration risk score threshold should reject overly risky plans.
        StorageSchemaSnapshot previous = new StorageSchemaSnapshot(
                "baseline",
                List.of(
                        new StorageTableSchema(
                                "patients",
                                List.of(
                                        new StorageColumnSchema("id", "UUID", true, true),
                                        new StorageColumnSchema("first_name", "VARCHAR", true, false),
                                        new StorageColumnSchema("status", "VARCHAR", false, false),
                                        new StorageColumnSchema("retired_code", "VARCHAR", false, false)
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

        MigrationRiskAssessment assessment = new MigrationRiskAssessmentBuilder().build(previous, current);

        assertTrue(assessment.deterministicPlan());
        assertEquals("BREAKING", assessment.overallRisk());
        assertTrue(assessment.safeChanges().stream().anyMatch(value -> value.contains("discover table insurance_claims")));
        assertTrue(assessment.safeChanges().stream().anyMatch(value -> value.contains("patients.preferred_language")));
        assertTrue(assessment.backfillRequiredChanges().stream().anyMatch(value -> value.contains("tighten required patients.status")));
        assertTrue(assessment.manualReviewChanges().stream().anyMatch(value -> value.contains("change type patients.status from VARCHAR to INTEGER")));
        assertTrue(assessment.breakingChanges().stream().anyMatch(value -> value.contains("remove column patients.retired_code")));
        assertTrue(assessment.manualReviewChanges().stream().anyMatch(value -> value.contains("change type")),
                "A dangerous operation should carry a risk score threshold review.");
    }
}

