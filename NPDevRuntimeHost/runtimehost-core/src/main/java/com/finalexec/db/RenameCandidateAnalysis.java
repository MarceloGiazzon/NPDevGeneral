package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentColumn;
import com.finalexec.db.schemastate.CurrentForeignKey;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentTable;
import com.finalexec.db.schemastate.DesiredColumn;
import com.finalexec.db.schemastate.DesiredForeignKey;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.DesiredTable;
import com.finalexec.db.schemastate.Resolution;
import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.npdev.dsl.v1.schemaevolution.RenameCandidateScorer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Boundary lift plan 2026-09-02, package 2.2 (B1). Wires {@link RenameCandidateScorer} into the
 * live-database Impact Report.
 *
 * <p>The flattened {@link ImpactReport}/{@link SchemaDiffItem} the report renders from keeps only
 * before/after strings (see those classes' own javadoc) -- not enough for a real score. The FULL
 * column facts the scorer needs (nullability, default, unique membership, FK target, ordinal
 * position) are still available one step earlier, in the {@link CurrentSchema}/{@link DesiredSchema}
 * pair {@link SchemaImpactFacade}, {@link ImpactReportWriter} and {@link SchemaVerifyMain} each
 * already build to run {@code SchemaDiffEngine.diff}. This class runs right there and returns a
 * ranked candidate list threaded alongside the report the same way {@code ConstraintSurplusReport}
 * already is -- a second value, never folded into {@link SchemaDiffItem} itself (that would change
 * the destructive-token hash inputs).
 */
final class RenameCandidateAnalysis {

    private RenameCandidateAnalysis() {
    }

    /**
     * Every DESTRUCTIVE_DROP_COLUMN item with live rows at stake, scored against every pending
     * ADD_COLUMN/ADD_REQUIRED_COLUMN item on the SAME table -- the identical eligibility filter
     * {@code ImpactReportText}'s original heuristic used, now scored instead of type-matched.
     */
    static List<RenameCandidateScorer.Candidate> compute(ImpactReport report, DesiredSchema desired, CurrentSchema current) {
        Map<String, List<String>> droppedByTable = new LinkedHashMap<>();
        Map<String, List<String>> addedByTable = new LinkedHashMap<>();
        for (ImpactReport.Item item : report.items()) {
            SchemaDiffItem di = item.diffItem();
            if (isEligibleDrop(item)) {
                droppedByTable.computeIfAbsent(di.table(), t -> new ArrayList<>()).add(di.column());
            } else if (isEligibleAdd(di)) {
                addedByTable.computeIfAbsent(di.table(), t -> new ArrayList<>()).add(di.column());
            }
        }
        List<RenameCandidateScorer.Candidate> all = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : droppedByTable.entrySet()) {
            String table = entry.getKey();
            List<String> addedColumns = addedByTable.get(table);
            if (addedColumns == null || addedColumns.isEmpty()) {
                continue;
            }
            CurrentTable currentTable = current.tables().get(table);
            DesiredTable desiredTable = desired.tables().get(table);
            if (currentTable == null || desiredTable == null) {
                continue;
            }
            List<RenameCandidateScorer.ColumnFacts> droppedFacts = entry.getValue().stream()
                    .map(column -> currentColumnFacts(currentTable, column))
                    .filter(Objects::nonNull)
                    .toList();
            List<RenameCandidateScorer.ColumnFacts> addedFacts = addedColumns.stream()
                    .map(column -> desiredColumnFacts(desiredTable, column))
                    .filter(Objects::nonNull)
                    .toList();
            if (droppedFacts.isEmpty() || addedFacts.isEmpty()) {
                continue;
            }
            all.addAll(RenameCandidateScorer.score(table, droppedFacts, addedFacts));
        }
        return List.copyOf(all);
    }

    private static boolean isEligibleDrop(ImpactReport.Item item) {
        SchemaDiffItem di = item.diffItem();
        return di.safetyClass() == SafetyClass.DESTRUCTIVE_DROP_COLUMN
                && di.resolution() == Resolution.UNRESOLVED
                && item.rowsAffected() > 0;
    }

    private static boolean isEligibleAdd(SchemaDiffItem di) {
        return (di.itemKey().startsWith("ADD_COLUMN:") || di.itemKey().startsWith("ADD_REQUIRED_COLUMN:"))
                && di.resolution() == Resolution.UNRESOLVED;
    }

    private static RenameCandidateScorer.ColumnFacts currentColumnFacts(CurrentTable table, String columnName) {
        CurrentColumn column = table.columns().get(columnName);
        if (column == null) {
            return null;
        }
        boolean unique = table.uniques().stream().anyMatch(u -> u.columns().contains(columnName));
        String fkTarget = table.foreignKeys().stream()
                .filter(fk -> fk.columns().contains(columnName))
                .map(CurrentForeignKey::referencedTable)
                .findFirst().orElse(null);
        return new RenameCandidateScorer.ColumnFacts(column.name(), column.normalizedSqlType(), column.nullable(),
                column.defaultValueNormalized(), unique, fkTarget, ordinalOf(table.columns().keySet(), columnName));
    }

    private static RenameCandidateScorer.ColumnFacts desiredColumnFacts(DesiredTable table, String columnName) {
        DesiredColumn column = table.columns().get(columnName);
        if (column == null) {
            return null;
        }
        boolean unique = table.uniques().stream().anyMatch(u -> u.columns().contains(columnName));
        String fkTarget = table.foreignKeys().stream()
                .filter(fk -> fk.columns().contains(columnName))
                .map(DesiredForeignKey::referencedTable)
                .findFirst().orElse(null);
        return new RenameCandidateScorer.ColumnFacts(column.name(), column.normalizedSqlType(), column.nullable(),
                column.literalDefault(), unique, fkTarget, ordinalOf(table.columns().keySet(), columnName));
    }

    /** {@code columns()} is a {@code LinkedHashMap} on both the reader and the factory side, so
     *  iteration order is the JDBC/model declaration order -- a real ordinal, not an arbitrary one. */
    private static int ordinalOf(Set<String> orderedColumnNames, String columnName) {
        int position = 0;
        for (String name : orderedColumnNames) {
            if (name.equals(columnName)) {
                return position;
            }
            position++;
        }
        return -1;
    }
}
