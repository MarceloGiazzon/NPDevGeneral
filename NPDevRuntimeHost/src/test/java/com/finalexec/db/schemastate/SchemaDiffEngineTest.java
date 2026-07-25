package com.finalexec.db.schemastate;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test (schema-engine rebuild, P2.5) — no DB. Drives {@link SchemaDiffEngine} across every
 * {@link SafetyClass} and the highest-stakes rule (rename resolved BEFORE it can look like drop+add).
 * RED until P2.4: the stub returns an empty diff, so every non-empty expectation fails.
 */
class SchemaDiffEngineTest {

    private final SchemaDiffEngine engine = new SchemaDiffEngine();

    @Test
    void identicalSchemasProduceEmptyDiff() {
        DesiredSchema desired = desired(dTable("orders", dCol("id", "UUID", false, null, true, true)));
        CurrentSchema current = current(cTable("orders", cCol("id", "UUID", false)));
        assertTrue(engine.diff(desired, current).isEmpty(), "no changes -> empty diff");
    }

    @Test
    void newTableIsSafeTableCreate() {
        DesiredSchema desired = desired(dTable("orders", dCol("id", "UUID", false, null, true, true)));
        CurrentSchema current = current();
        assertEquals(SafetyClass.SAFE_TABLE_CREATE, only(engine.diff(desired, current)).safetyClass());
    }

    @Test
    void droppedTableIsDestructive() {
        DesiredSchema desired = desired();
        CurrentSchema current = current(cTable("orders", cCol("id", "UUID", false)));
        SchemaDiffItem item = only(engine.diff(desired, current));
        assertEquals(SafetyClass.DESTRUCTIVE_DROP_TABLE, item.safetyClass());
        assertEquals("DROP_TABLE:orders", item.itemKey(), "destructive key must match SchemaDeltaItem format");
    }

    @Test
    void newNullableColumnIsSafeAdditive() {
        SafetyClass sc = columnAddChange(dCol("note", "VARCHAR(50)", true, null, false, false));
        assertEquals(SafetyClass.SAFE_ADDITIVE, sc);
    }

    @Test
    void newRequiredColumnWithDefaultNeedsBackfill() {
        SafetyClass sc = columnAddChange(dCol("state", "VARCHAR(10)", false, "\"new\"", false, true));
        assertEquals(SafetyClass.NEEDS_BACKFILL, sc);
    }

    @Test
    void newRequiredColumnWithoutDefaultNeedsHook() {
        SafetyClass sc = columnAddChange(dCol("owner", "VARCHAR(10)", false, null, false, true));
        assertEquals(SafetyClass.NEEDS_HOOK, sc);
    }

    @Test
    void droppedColumnIsDestructive() {
        DesiredSchema desired = desired(dTable("orders", dCol("id", "UUID", false, null, true, true)));
        CurrentSchema current = current(cTable("orders",
                cCol("id", "UUID", false), cCol("gone", "VARCHAR(9)", true)));
        SchemaDiffItem item = only(engine.diff(desired, current));
        assertEquals(SafetyClass.DESTRUCTIVE_DROP_COLUMN, item.safetyClass());
        assertTrue(item.itemKey().startsWith("DROP_COLUMN:orders:gone:"), item.itemKey());
    }

    @Test
    void wideningTypeIsSafeWiden() {
        SafetyClass sc = typeChange("INTEGER", "BIGINT");
        assertEquals(SafetyClass.SAFE_WIDEN, sc);
    }

    @Test
    void narrowingTypeIsDestructive() {
        SafetyClass sc = typeChange("BIGINT", "INTEGER");
        assertEquals(SafetyClass.DESTRUCTIVE_NARROW_TYPE, sc);
    }

    @Test
    void declaredRenameIsSafeRenameNotDropAdd() {
        DesiredSchema desired = desired(dTable("orders",
                dCol("id", "UUID", false, null, true, true),
                dColRenamed("email", "VARCHAR(200)", true, "email_addr")));
        CurrentSchema current = current(cTable("orders",
                cCol("id", "UUID", false), cCol("email_addr", "VARCHAR(200)", true)));
        SchemaDiff diff = engine.diff(desired, current);
        assertEquals(1, diff.items().size(), "a declared rename is ONE rename item, not drop+add: " + diff.items());
        assertEquals(SafetyClass.SAFE_RENAME, diff.items().get(0).safetyClass());
    }

    @Test
    void undeclaredRenameLooksLikeDropPlusAdd() {
        DesiredSchema desired = desired(dTable("orders",
                dCol("id", "UUID", false, null, true, true),
                dCol("email", "VARCHAR(200)", true, null, false, false)));
        CurrentSchema current = current(cTable("orders",
                cCol("id", "UUID", false), cCol("email_addr", "VARCHAR(200)", true)));
        SchemaDiff diff = engine.diff(desired, current);
        List<SafetyClass> classes = diff.items().stream().map(SchemaDiffItem::safetyClass).toList();
        assertTrue(classes.contains(SafetyClass.SAFE_ADDITIVE) && classes.contains(SafetyClass.DESTRUCTIVE_DROP_COLUMN),
                "no rename hint -> drop old + add new: " + classes);
    }

    @Test
    void declaredTableRenameIsSafeRename() {
        DesiredSchema desired = desired(dTableRenamed("clients", "old_clients",
                dCol("id", "UUID", false, null, true, true)));
        CurrentSchema current = current(cTable("old_clients", cCol("id", "UUID", false)));
        SchemaDiff diff = engine.diff(desired, current);
        assertTrue(diff.items().stream().anyMatch(i -> i.safetyClass() == SafetyClass.SAFE_RENAME),
                "declared table rename -> SAFE_RENAME: " + diff.items());
        assertTrue(diff.items().stream().noneMatch(i -> i.safetyClass() == SafetyClass.DESTRUCTIVE_DROP_TABLE),
                "a renamed table must NOT be reported as a drop: " + diff.items());
    }

    @Test
    void nullabilityRelaxationIsSafeRelax() {
        DesiredSchema desired = desired(dTable("orders",
                dCol("id", "UUID", false, null, true, true),
                dCol("note", "VARCHAR(50)", true, null, false, false)));   // now optional
        CurrentSchema current = current(cTable("orders",
                cCol("id", "UUID", false), cCol("note", "VARCHAR(50)", false)));  // was NOT NULL
        assertEquals(SafetyClass.SAFE_RELAX, only(engine.diff(desired, current)).safetyClass());
    }

    @Test
    void loosenedPlatformColumnBecomesATightenItem() {
        DesiredSchema desired = desired(dTable("orders",
                dCol("id", "UUID", false, null, true, true),
                dCol("tenant_id", "VARCHAR(120)", false, "\"default\"", true, false))); // platform, NOT NULL
        CurrentSchema current = current(cTable("orders",
                cCol("id", "UUID", false), cCol("tenant_id", "VARCHAR(120)", true))); // loosened to nullable
        SchemaDiffItem item = only(engine.diff(desired, current));
        assertEquals(SafetyClass.NEEDS_BACKFILL, item.safetyClass(),
                "a loosened platform column is repaired (backfill+tighten), never SAFE_RELAX");
        assertTrue(item.itemKey().startsWith("TIGHTEN_PLATFORM:"), item.itemKey());
    }

    @Test
    void declaredForeignKeyMissingLiveIsReportedAsSafeAdditive() {
        DesiredTable orders = new DesiredTable("orders",
                Map.of("owner_id", dCol("owner_id", "UUID", true, null, false, false)),
                List.of(), null,
                List.of(new DesiredForeignKey(List.of("owner_id"), "owners", List.of("id"))),
                List.of());
        DesiredSchema desired = desired(orders);
        CurrentSchema current = current(cTable("orders", cCol("owner_id", "UUID", true)));

        SchemaDiffItem item = only(engine.diff(desired, current));
        assertEquals(SafetyClass.SAFE_ADDITIVE, item.safetyClass(),
                "adding a missing FK destroys nothing -- it must not change any classification verdict");
        assertTrue(item.itemKey().startsWith("ADD_FOREIGN_KEY:orders:owner_id:owners"), item.itemKey());
    }

    @Test
    void aLiveForeignKeyWithADifferentNameStillSatisfiesTheDeclaredOne() {
        DesiredTable orders = new DesiredTable("orders",
                Map.of("owner_id", dCol("owner_id", "UUID", true, null, false, false)),
                List.of(), null,
                List.of(new DesiredForeignKey(List.of("owner_id"), "owners", List.of("id"))),
                List.of());
        CurrentTable live = new CurrentTable("orders",
                Map.of("owner_id", cCol("owner_id", "UUID", true)),
                List.of("id"), List.of(),
                // engine-chosen name -- matching must ignore it
                List.of(new CurrentForeignKey("CONSTRAINT_8F", List.of("owner_id"), "owners", List.of("id"), null)),
                List.of());
        assertTrue(engine.diff(desired(orders), current(live)).isEmpty(),
                "an FK is matched by column set + referenced table, never by its engine-generated name");
    }

    @Test
    void extraLiveIndexesAreNeverReported() {
        // The live side always carries implicit PK/unique indexes the model never declares. Reporting
        // them would propose dropping primary-key indexes -- this pins that we never do.
        DesiredTable orders = new DesiredTable("orders",
                Map.of("id", dCol("id", "UUID", false, null, true, true)),
                List.of(), null, List.of(), List.of());
        CurrentTable live = new CurrentTable("orders",
                Map.of("id", cCol("id", "UUID", false)),
                List.of("id"), List.of(), List.of(),
                List.of(new CurrentIndex("PRIMARY_KEY_5", List.of("id"), true),
                        new CurrentIndex("a_dbas_own_perf_index", List.of("id"), false)));
        assertTrue(engine.diff(desired(orders), current(live)).isEmpty(),
                "extra live indexes must never appear in the diff (missing-only by design)");
    }

    @Test
    void aPrimaryKeySatisfiesADeclaredIndexOverTheSameColumns() {
        DesiredTable orders = new DesiredTable("orders",
                Map.of("id", dCol("id", "UUID", false, null, true, true)),
                List.of(), null, List.of(),
                List.of(new DesiredIndex(List.of("id"), false)));
        CurrentTable live = new CurrentTable("orders",
                Map.of("id", cCol("id", "UUID", false)),
                List.of("id"), List.of(), List.of(), List.of());
        assertTrue(engine.diff(desired(orders), current(live)).isEmpty(),
                "a live PK over the same columns satisfies a declared index");
    }

    // ---- helpers ----

    private SafetyClass columnAddChange(DesiredColumn added) {
        DesiredSchema desired = desired(dTable("orders", dCol("id", "UUID", false, null, true, true), added));
        CurrentSchema current = current(cTable("orders", cCol("id", "UUID", false)));
        return only(engine.diff(desired, current)).safetyClass();
    }

    private SafetyClass typeChange(String currentType, String desiredType) {
        DesiredSchema desired = desired(dTable("orders",
                dCol("id", "UUID", false, null, true, true),
                dCol("qty", desiredType, true, null, false, false)));
        CurrentSchema current = current(cTable("orders",
                cCol("id", "UUID", false), cCol("qty", currentType, true)));
        return only(engine.diff(desired, current)).safetyClass();
    }

    private static SchemaDiffItem only(SchemaDiff diff) {
        assertEquals(1, diff.items().size(), "expected exactly one diff item: " + diff.items());
        return diff.items().get(0);
    }

    private static DesiredSchema desired(DesiredTable... tables) {
        Map<String, DesiredTable> map = new LinkedHashMap<>();
        for (DesiredTable t : tables) {
            map.put(t.name(), t);
        }
        return new DesiredSchema(map);
    }

    private static CurrentSchema current(CurrentTable... tables) {
        Map<String, CurrentTable> map = new LinkedHashMap<>();
        for (CurrentTable t : tables) {
            map.put(t.name(), t);
        }
        return new CurrentSchema(map);
    }

    private static DesiredTable dTable(String name, DesiredColumn... cols) {
        return dTableRenamed(name, null, cols);
    }

    private static DesiredTable dTableRenamed(String name, String renamedFrom, DesiredColumn... cols) {
        Map<String, DesiredColumn> map = new LinkedHashMap<>();
        for (DesiredColumn c : cols) {
            map.put(c.name(), c);
        }
        return new DesiredTable(name, map, List.of(), renamedFrom);
    }

    private static DesiredColumn dCol(String name, String type, boolean nullable, String def,
            boolean platform, boolean required) {
        // non-bond columns are always additive-eligible
        return new DesiredColumn(name, type, nullable, def, platform, required, false, true, null);
    }

    private static DesiredColumn dColRenamed(String name, String type, boolean nullable, String renamedFrom) {
        return new DesiredColumn(name, type, nullable, null, false, false, false, true, renamedFrom);
    }

    private static CurrentTable cTable(String name, CurrentColumn... cols) {
        Map<String, CurrentColumn> map = new LinkedHashMap<>();
        for (CurrentColumn c : cols) {
            map.put(c.name(), c);
        }
        return new CurrentTable(name, map, List.of("id"), List.of(), List.of(), List.of());
    }

    private static CurrentColumn cCol(String name, String type, boolean nullable) {
        return new CurrentColumn(name, type, null, null, nullable, null);
    }
}
