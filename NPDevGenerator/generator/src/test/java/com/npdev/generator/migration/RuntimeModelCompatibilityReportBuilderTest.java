package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeModelCompatibilityReportBuilderTest {

    @Test
    void shouldFlagCompatibilityWarningsAndBlockingIssues() {
        StorageSchemaSnapshot previous = new StorageSchemaSnapshot(
                "baseline",
                List.of(
                        new StorageTableSchema(
                                "patients",
                                List.of(
                                        new StorageColumnSchema("id", "UUID", true, true),
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
                                        new StorageColumnSchema("status", "INTEGER", true, false),
                                        new StorageColumnSchema("preferred_language", "VARCHAR", false, false)
                                )
                        )
                )
        );

        Properties buildInfo = new Properties();
        buildInfo.setProperty("npdev.version", "0.1.0-test");
        buildInfo.setProperty("npdev.builtAt", "2026-03-09T00:00:00Z");

        RuntimeModelCompatibilityReport report = new RuntimeModelCompatibilityReportBuilder().build(previous, current, buildInfo);

        assertFalse(report.compatible());
        assertEquals("INCOMPATIBLE", report.compatibilityStatus());
        assertEquals("0.1.0-test", report.runtimeBuildVersion());
        assertTrue(report.warnings().stream().anyMatch(value -> value.contains("tighten required patients.status")));
        assertTrue(report.warnings().stream().anyMatch(value -> value.contains("change type patients.status from VARCHAR to INTEGER")));
        assertTrue(report.blockingIssues().stream().anyMatch(value -> value.contains("remove column patients.retired_code")));
    }
}

