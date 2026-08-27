package com.finalexec.db.datamobility;

import com.finalexec.db.schemastate.CurrentColumn;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentTable;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the {@code desired=TARGET, current=SOURCE} directionality {@link DataMobilityStructureCheck}
 * depends on -- see that class's javadoc for the full derivation. No database at all: {@link
 * CurrentSchema}/{@link CurrentTable}/{@link CurrentColumn} are plain records, built by hand here, so
 * these assertions are a pure function of the bucketing logic and cannot be confused by an engine's own
 * quirks. The real-engine round trip lives in {@link DataMobilityStructureCheckH2Test}.
 *
 * <p>The first draft of this feature had {@code desired}/{@code current} swapped -- every WIDENING/
 * NARROWING verdict below would come out backwards under that swap, which is exactly the class of bug a
 * compile-only review cannot catch (both directions compile). These tests exist specifically to make
 * that mistake fail loudly if it is ever reintroduced.
 */
class DataMobilityStructureCheckDirectionTest {

    @Test
    void identicalSchemasAreEqual() {
        CurrentSchema source = schemaOf(table("widgets", column("name", "VARCHAR(100)", false)));
        CurrentSchema target = schemaOf(table("widgets", column("name", "VARCHAR(100)", false)));

        StructureCheckResult result = DataMobilityStructureCheck.check(source, target, "h2", false);

        assertEquals(StructureVerdict.EQUAL, result.verdict());
        assertTrue(result.incompatibleReasons().isEmpty());
        assertTrue(result.compatibleReasons().isEmpty());
    }

    @Test
    void targetColumnNarrowerThanSource_isIncompatible() {
        // Source can hold up to 100 characters; target only 50 -- writing source's data could truncate.
        CurrentSchema source = schemaOf(table("widgets", column("name", "VARCHAR(100)", false)));
        CurrentSchema target = schemaOf(table("widgets", column("name", "VARCHAR(50)", false)));

        StructureCheckResult result = DataMobilityStructureCheck.check(source, target, "h2", false);

        assertEquals(StructureVerdict.INCOMPATIBLE, result.verdict());
        assertTrue(result.incompatibleReasons().stream().anyMatch(r -> r.contains("widgets.name")),
                "expected a widgets.name reason, got: " + result.incompatibleReasons());
    }

    @Test
    void targetColumnWiderThanSource_isCompatible() {
        // The reverse of the above: target has MORE capacity than source needs -- every source value fits.
        CurrentSchema source = schemaOf(table("widgets", column("name", "VARCHAR(50)", false)));
        CurrentSchema target = schemaOf(table("widgets", column("name", "VARCHAR(100)", false)));

        StructureCheckResult result = DataMobilityStructureCheck.check(source, target, "h2", false);

        assertEquals(StructureVerdict.COMPATIBLE, result.verdict());
        assertTrue(result.incompatibleReasons().isEmpty());
        assertFalse(result.compatibleReasons().isEmpty());
    }

    @Test
    void targetHasExtraNullableColumn_isCompatible() {
        CurrentSchema source = schemaOf(table("widgets", column("name", "VARCHAR(100)", false)));
        CurrentSchema target = schemaOf(table("widgets",
                column("name", "VARCHAR(100)", false),
                column("notes", "VARCHAR(255)", true)));

        StructureCheckResult result = DataMobilityStructureCheck.check(source, target, "h2", false);

        assertEquals(StructureVerdict.COMPATIBLE, result.verdict());
    }

    @Test
    void targetHasExtraRequiredColumnWithNoDefault_isIncompatible() {
        // Target demands a value for "code" on every row; source has no way to supply one, and this
        // adapter never threads a live DEFAULT through (see DataMobilityStructureCheck#adaptToDesired) --
        // so this is a real, if conservative, blocker.
        CurrentSchema source = schemaOf(table("widgets", column("name", "VARCHAR(100)", false)));
        CurrentSchema target = schemaOf(table("widgets",
                column("name", "VARCHAR(100)", false),
                column("code", "VARCHAR(50)", false)));

        StructureCheckResult result = DataMobilityStructureCheck.check(source, target, "h2", false);

        assertEquals(StructureVerdict.INCOMPATIBLE, result.verdict());
        assertTrue(result.incompatibleReasons().stream().anyMatch(r -> r.contains("widgets.code")),
                "expected a widgets.code reason, got: " + result.incompatibleReasons());
    }

    @Test
    void sourceHasColumnMissingFromTarget_isIncompatibleWithoutDdl_compatibleWithDdl() {
        CurrentSchema source = schemaOf(table("widgets",
                column("name", "VARCHAR(100)", false),
                column("legacy_code", "VARCHAR(30)", true)));
        CurrentSchema target = schemaOf(table("widgets", column("name", "VARCHAR(100)", false)));

        StructureCheckResult withoutDdl = DataMobilityStructureCheck.check(source, target, "h2", false);
        assertEquals(StructureVerdict.INCOMPATIBLE, withoutDdl.verdict());
        assertTrue(withoutDdl.incompatibleReasons().stream().anyMatch(r -> r.contains("widgets.legacy_code")),
                "expected a widgets.legacy_code reason, got: " + withoutDdl.incompatibleReasons());

        StructureCheckResult withDdl = DataMobilityStructureCheck.check(source, target, "h2", true);
        assertEquals(StructureVerdict.COMPATIBLE, withDdl.verdict());
        assertTrue(withDdl.compatibleReasons().stream().anyMatch(r -> r.contains("legacy_code")),
                "expected the DDL-hint reason to name legacy_code, got: " + withDdl.compatibleReasons());
    }

    @Test
    void sourceHasTableMissingFromTarget_isIncompatibleWithoutDdl_compatibleWithDdl() {
        CurrentSchema source = schemaOf(
                table("widgets", column("name", "VARCHAR(100)", false)),
                table("legacy_gadgets", column("id", "VARCHAR(36)", false)));
        CurrentSchema target = schemaOf(table("widgets", column("name", "VARCHAR(100)", false)));

        StructureCheckResult withoutDdl = DataMobilityStructureCheck.check(source, target, "h2", false);
        assertEquals(StructureVerdict.INCOMPATIBLE, withoutDdl.verdict());
        assertTrue(withoutDdl.incompatibleReasons().stream().anyMatch(r -> r.contains("legacy_gadgets")),
                "expected a legacy_gadgets reason, got: " + withoutDdl.incompatibleReasons());

        StructureCheckResult withDdl = DataMobilityStructureCheck.check(source, target, "h2", true);
        assertEquals(StructureVerdict.COMPATIBLE, withDdl.verdict());
    }

    @Test
    void sharedColumn_targetNotNullSourceNullable_isIncompatible() {
        // Target rejects NULL on every row; source cannot guarantee it never sends one.
        CurrentSchema source = schemaOf(table("widgets", column("name", "VARCHAR(100)", true)));
        CurrentSchema target = schemaOf(table("widgets", column("name", "VARCHAR(100)", false)));

        StructureCheckResult result = DataMobilityStructureCheck.check(source, target, "h2", false);

        assertEquals(StructureVerdict.INCOMPATIBLE, result.verdict());
        assertTrue(result.incompatibleReasons().stream().anyMatch(r -> r.contains("widgets.name")),
                "expected a widgets.name reason, got: " + result.incompatibleReasons());
    }

    @Test
    void sharedColumn_targetNullableSourceNotNull_isCompatible() {
        // Target permits NULL; source's guaranteed-non-null values fit with room to spare.
        CurrentSchema source = schemaOf(table("widgets", column("name", "VARCHAR(100)", false)));
        CurrentSchema target = schemaOf(table("widgets", column("name", "VARCHAR(100)", true)));

        StructureCheckResult result = DataMobilityStructureCheck.check(source, target, "h2", false);

        assertEquals(StructureVerdict.COMPATIBLE, result.verdict());
    }

    // ------------------------------------------------------------------ fixtures

    private static CurrentSchema schemaOf(CurrentTable... tables) {
        Map<String, CurrentTable> map = new LinkedHashMap<>();
        for (CurrentTable t : tables) {
            map.put(t.name(), t);
        }
        return new CurrentSchema(Map.copyOf(map));
    }

    private static CurrentTable table(String name, CurrentColumn... columns) {
        Map<String, CurrentColumn> map = new LinkedHashMap<>();
        for (CurrentColumn c : columns) {
            map.put(c.name(), c);
        }
        return new CurrentTable(name, Map.copyOf(map), List.of(), List.of(), List.of(), List.of());
    }

    private static CurrentColumn column(String name, String normalizedSqlType, boolean nullable) {
        return new CurrentColumn(name, normalizedSqlType, null, null, nullable, null);
    }
}
