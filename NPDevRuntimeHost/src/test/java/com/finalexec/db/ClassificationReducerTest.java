package com.finalexec.db;

import com.finalexec.db.schemastate.DesiredColumn;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.DesiredTable;
import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.finalexec.db.SchemaLifecycleExecutor.SchemaChangeClassification.DESTRUCTIVE;
import static com.finalexec.db.SchemaLifecycleExecutor.SchemaChangeClassification.RENAME_DETECTED;
import static com.finalexec.db.SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE;
import static com.finalexec.db.SchemaLifecycleExecutor.SchemaChangeClassification.TYPE_CHANGE_DETECTED;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure unit test (schema-engine rebuild, P4.1): {@link ClassificationReducer} maps a {@link SchemaDiff}
 * to the live {@code SchemaChangeClassification} exactly as {@code classify}'s worst-wins aggregation
 * would. RED until the real reduction lands (the stub returns SAFE_ADDITIVE for everything).
 */
class ClassificationReducerTest {

    // A DesiredSchema in which orders.owner_id is a BOND and orders.owner is a plain (non-bond) column,
    // so the two NEEDS_HOOK cases can be disambiguated.
    private static final DesiredSchema DESIRED = new DesiredSchema(Map.of(
            "orders", new DesiredTable("orders", Map.of(
                    "owner_id", new DesiredColumn("owner_id", "UUID", false, null, false, true, true, null),
                    "owner", new DesiredColumn("owner", "VARCHAR(20)", false, null, false, true, false, null)
            ), List.of(), null)));

    private static SchemaChange reduce(SchemaDiffItem... items) {
        return new SchemaChange(ClassificationReducer.reduce(new SchemaDiff(List.of(items)), DESIRED));
    }

    private record SchemaChange(SchemaLifecycleExecutor.SchemaChangeClassification value) {
    }

    private static SchemaDiffItem item(SafetyClass sc, String column) {
        return SchemaDiffItem.of(sc + ":orders:" + column, "orders", column, sc, null, null);
    }

    @Test
    void emptyDiffIsSafeAdditive() {
        assertEquals(SAFE_ADDITIVE, ClassificationReducer.reduce(new SchemaDiff(List.of()), DESIRED));
    }

    @Test
    void additiveRelaxBackfillAreAllSafeAdditive() {
        assertEquals(SAFE_ADDITIVE, reduce(item(SafetyClass.SAFE_ADDITIVE, "note")).value());
        assertEquals(SAFE_ADDITIVE, reduce(item(SafetyClass.SAFE_RELAX, "note")).value());
        assertEquals(SAFE_ADDITIVE, reduce(item(SafetyClass.NEEDS_BACKFILL, "state")).value());
        assertEquals(SAFE_ADDITIVE, reduce(item(SafetyClass.SAFE_TABLE_CREATE, null)).value());
    }

    @Test
    void needsHookNonBondIsAdditiveButBondIsDestructive() {
        // required non-bond, no default -> classify treats it as additive-eligible (SAFE_ADDITIVE),
        // the backfill pass refuses it later.
        assertEquals(SAFE_ADDITIVE, reduce(item(SafetyClass.NEEDS_HOOK, "owner")).value());
        // required BOND -> classify cannot add it -> DESTRUCTIVE.
        assertEquals(DESTRUCTIVE, reduce(item(SafetyClass.NEEDS_HOOK, "owner_id")).value());
    }

    @Test
    void renameIsRenameDetectedAndWidenIsTypeChange() {
        assertEquals(RENAME_DETECTED, reduce(item(SafetyClass.SAFE_RENAME, "email")).value());
        assertEquals(TYPE_CHANGE_DETECTED, reduce(item(SafetyClass.SAFE_WIDEN, "qty")).value());
    }

    @Test
    void destructiveItemsAreDestructive() {
        assertEquals(DESTRUCTIVE, reduce(item(SafetyClass.DESTRUCTIVE_DROP_COLUMN, "gone")).value());
        assertEquals(DESTRUCTIVE, reduce(item(SafetyClass.DESTRUCTIVE_DROP_TABLE, null)).value());
        assertEquals(DESTRUCTIVE, reduce(item(SafetyClass.DESTRUCTIVE_NARROW_TYPE, "name")).value());
    }

    @Test
    void worstWins() {
        // rename + widen -> TYPE_CHANGE_DETECTED (worse than rename)
        assertEquals(TYPE_CHANGE_DETECTED,
                reduce(item(SafetyClass.SAFE_RENAME, "email"), item(SafetyClass.SAFE_WIDEN, "qty")).value());
        // rename + drop -> DESTRUCTIVE (worst)
        assertEquals(DESTRUCTIVE,
                reduce(item(SafetyClass.SAFE_RENAME, "email"), item(SafetyClass.DESTRUCTIVE_DROP_COLUMN, "gone")).value());
    }
}
