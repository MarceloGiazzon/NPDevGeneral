package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3.2 (B4 migrate-only + progress-aware waiting): {@link SchemaLifecycleExecutor#migrateOnlyRequested}
 * is the testable half of the MIGRATE_ONLY switch -- this test never touches a DataSource or calls
 * {@code System.exit}. The exit itself ({@link SchemaLifecycleExecutor#exitIfMigrateOnly}) is
 * exercised only via a real {@code java -jar} process ({@code Migrate-Only.ps1}), the same discipline
 * {@code SchemaLifecycleExecutorReportOnlyTest} already documents for REPORT_ONLY's own exit.
 */
class SchemaLifecycleExecutorMigrateOnlyTest {

    private static final String PROPERTY = "npdev.schema.lifecycle.mode";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void notRequestedWhenPropertyUnset() {
        System.clearProperty(PROPERTY);
        assertFalse(SchemaLifecycleExecutor.migrateOnlyRequested());
    }

    @Test
    void requestedWhenPropertyIsMigrateOnly() {
        System.setProperty(PROPERTY, "MIGRATE_ONLY");
        assertTrue(SchemaLifecycleExecutor.migrateOnlyRequested());
    }

    @Test
    void caseInsensitive() {
        System.setProperty(PROPERTY, "migrate_only");
        assertTrue(SchemaLifecycleExecutor.migrateOnlyRequested());
    }

    @Test
    void notRequestedForReportOnlyOrApply() {
        System.setProperty(PROPERTY, "REPORT_ONLY");
        assertFalse(SchemaLifecycleExecutor.migrateOnlyRequested());
        System.setProperty(PROPERTY, "APPLY");
        assertFalse(SchemaLifecycleExecutor.migrateOnlyRequested());
    }
}
