package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MigrationDiffEngineTest {

    @Test
    void shouldGenerateAdditiveOperationsDeterministically() {
        StorageSchemaSnapshot previous = new StorageSchemaSnapshot(
                "v1",
                List.of(
                        new StorageTableSchema(
                                "user",
                                List.of(
                                        new StorageColumnSchema("id", "UUID", true, true),
                                        new StorageColumnSchema("email", "VARCHAR", false, false)
                                )
                        )
                )
        );

        StorageSchemaSnapshot current = new StorageSchemaSnapshot(
                "v2",
                List.of(
                        new StorageTableSchema(
                                "user",
                                List.of(
                                        new StorageColumnSchema("id", "UUID", true, true),
                                        new StorageColumnSchema("email", "VARCHAR", true, true),
                                        new StorageColumnSchema("active", "BOOLEAN", false, false)
                                )
                        )
                )
        );

        MigrationPlan plan = new MigrationDiffEngine().diff(previous, current);

        assertEquals(3, plan.operations().size());
        assertEquals(MigrationOperation.Kind.ADD_COLUMN, plan.operations().get(0).kind());
        assertEquals("active", plan.operations().get(0).columnName());

        assertEquals(MigrationOperation.Kind.SET_NOT_NULL, plan.operations().get(1).kind());
        assertEquals("email", plan.operations().get(1).columnName());

        assertEquals(MigrationOperation.Kind.CREATE_UNIQUE_INDEX, plan.operations().get(2).kind());
        assertEquals("email", plan.operations().get(2).columnName());
    }
}
