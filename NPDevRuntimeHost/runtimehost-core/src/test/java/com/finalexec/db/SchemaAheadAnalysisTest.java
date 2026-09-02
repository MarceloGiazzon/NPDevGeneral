package com.finalexec.db;

import com.finalexec.db.schemastate.DesiredColumn;
import com.finalexec.db.schemastate.DesiredForeignKey;
import com.finalexec.db.schemastate.DesiredIndex;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.DesiredTable;
import com.finalexec.db.schemastate.DesiredUniqueConstraint;
import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaAheadResolution;
import com.finalexec.db.schemastate.SchemaDiffItem;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B5-A (boundary-lift 2026-09-02, package 2.3). Pure, no-DB coverage: the {@code resolutionFor}
 * classification (every {@link SafetyClass} bucket done-when #3 asks for) and a round-trip of
 * {@link SchemaSnapshotStore}'s hand-rolled JSON codec -- the newest, least-precedented code this
 * package adds (see that class's javadoc for why it does not use {@code ObjectMapper.readValue(json,
 * DesiredSchema.class)} directly).
 */
class SchemaAheadAnalysisTest {

    @Test
    void destructiveItemsNeedADestructiveDowngrade() {
        assertEquals(SchemaAheadResolution.NEEDS_DESTRUCTIVE_DOWNGRADE, SchemaAheadAnalysis.resolutionFor(
                SchemaDiffItem.of("DROP_COLUMN:t:c", "t", "c", SafetyClass.DESTRUCTIVE_DROP_COLUMN, "VARCHAR(50)", null)));
        assertEquals(SchemaAheadResolution.NEEDS_DESTRUCTIVE_DOWNGRADE, SchemaAheadAnalysis.resolutionFor(
                SchemaDiffItem.of("DROP_TABLE:t", "t", null, SafetyClass.DESTRUCTIVE_DROP_TABLE, "t", null)));
        assertEquals(SchemaAheadResolution.NEEDS_DESTRUCTIVE_DOWNGRADE, SchemaAheadAnalysis.resolutionFor(
                SchemaDiffItem.of("NARROW:t:c", "t", "c", SafetyClass.DESTRUCTIVE_NARROW_TYPE, "VARCHAR(255)", "VARCHAR(10)")));
    }

    @Test
    void relaxingNotNullIsSafeToProceedIgnoring() {
        assertEquals(SchemaAheadResolution.PROCEED_IGNORING, SchemaAheadAnalysis.resolutionFor(
                SchemaDiffItem.of("RELAX_NOT_NULL:t:c", "t", "c", SafetyClass.SAFE_RELAX, "NULL", "NOT NULL")));
    }

    @Test
    void missingForeignKeyOrIndexIsAdvisoryOnlyLikeSchemaDiffEngineTreatsIt() {
        assertEquals(SchemaAheadResolution.PROCEED_IGNORING, SchemaAheadAnalysis.resolutionFor(
                SchemaDiffItem.of("ADD_FOREIGN_KEY:t:c:other", "t", "c", SafetyClass.SAFE_ADDITIVE, null, "other")));
        assertEquals(SchemaAheadResolution.PROCEED_IGNORING, SchemaAheadAnalysis.resolutionFor(
                SchemaDiffItem.of("ADD_INDEX:t:c", "t", "c", SafetyClass.SAFE_ADDITIVE, null, "c")));
    }

    @Test
    void missingStructureNeedsTheNewerBuild() {
        assertEquals(SchemaAheadResolution.NEEDS_NEWER_BUILD, SchemaAheadAnalysis.resolutionFor(
                SchemaDiffItem.of("CREATE_TABLE:t", "t", null, SafetyClass.SAFE_TABLE_CREATE, null, "t")));
        // A plain missing column (not FK/index) is the real "this build needs a column the live schema
        // does not have" case, unlike the advisory-only ADD_FOREIGN_KEY/ADD_INDEX items above.
        assertEquals(SchemaAheadResolution.NEEDS_NEWER_BUILD, SchemaAheadAnalysis.resolutionFor(
                SchemaDiffItem.of("ADD_COLUMN:t:c", "t", "c", SafetyClass.SAFE_ADDITIVE, null, "VARCHAR(50)")));
        assertEquals(SchemaAheadResolution.NEEDS_NEWER_BUILD, SchemaAheadAnalysis.resolutionFor(
                SchemaDiffItem.of("ADD_REQUIRED_COLUMN:t:c", "t", "c", SafetyClass.NEEDS_BACKFILL, null, "VARCHAR(50)")));
        assertEquals(SchemaAheadResolution.NEEDS_NEWER_BUILD, SchemaAheadAnalysis.resolutionFor(
                SchemaDiffItem.of("ADD_REQUIRED_COLUMN:t:c", "t", "c", SafetyClass.NEEDS_HOOK, null, "VARCHAR(50)")));
        assertEquals(SchemaAheadResolution.NEEDS_NEWER_BUILD, SchemaAheadAnalysis.resolutionFor(
                SchemaDiffItem.of("WIDEN_TYPE:t:c", "t", "c", SafetyClass.SAFE_WIDEN, "INT", "BIGINT")));
        assertEquals(SchemaAheadResolution.NEEDS_NEWER_BUILD, SchemaAheadAnalysis.resolutionFor(
                SchemaDiffItem.of("RENAME_COLUMN:t:old:new", "t", "new", SafetyClass.SAFE_RENAME, "old", "new")));
    }

    @Test
    void manualReviewDefaultsToNeedsTheNewerBuildRatherThanClaimingSafety() {
        assertEquals(SchemaAheadResolution.NEEDS_NEWER_BUILD, SchemaAheadAnalysis.resolutionFor(
                SchemaDiffItem.of("?:t:c", "t", "c", SafetyClass.MANUAL_REVIEW, "X", "Y")));
    }

    @Test
    void snapshotJsonRoundTripsEveryFieldOfADesiredSchema() {
        Map<String, DesiredColumn> columns = new LinkedHashMap<>();
        columns.put("id", new DesiredColumn("id", "BIGINT", false, null, true, true, false, true, null));
        columns.put("email", new DesiredColumn("email", "VARCHAR(255)", false, "'x@example.com'", false, true, false, true, "old_email"));
        columns.put("owner_id", new DesiredColumn("owner_id", "BIGINT", true, null, false, false, true, false, null));
        DesiredTable widgets = new DesiredTable(
                "widgets", columns,
                List.of(new DesiredUniqueConstraint(List.of("email"))),
                "widget",
                List.of(new DesiredForeignKey(List.of("owner_id"), "owners", List.of("id"))),
                List.of(new DesiredIndex(List.of("owner_id"), false)));
        DesiredSchema original = new DesiredSchema(Map.of("widgets", widgets));

        DesiredSchema roundTripped = SchemaSnapshotStore.fromJson(SchemaSnapshotStore.toJson(original));

        assertEquals(original, roundTripped);
    }

    @Test
    void snapshotJsonRoundTripsATableWithNoConstraints() {
        Map<String, DesiredColumn> columns = new LinkedHashMap<>();
        columns.put("id", new DesiredColumn("id", "BIGINT", false, null, true, true, false, true, null));
        DesiredTable plain = new DesiredTable("plain", columns, List.of(), null, List.of(), List.of());
        DesiredSchema original = new DesiredSchema(Map.of("plain", plain));

        DesiredSchema roundTripped = SchemaSnapshotStore.fromJson(SchemaSnapshotStore.toJson(original));

        assertEquals(original, roundTripped);
    }
}
