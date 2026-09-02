package com.finalexec.db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * STOR-18 (docs/ACCEPTED_BOUNDARIES.md B9): resolves the table SET a batch restore call covers (an
 * explicit list, or {@code "all"}/empty against every table {@link SchemaDropSnapshotRestorer}'s
 * snapshot captured) and orders it so a parent table restores before any child that FK-references it
 * -- pure, no {@link javax.sql.DataSource}, so the ordering logic is unit-testable without a real
 * database. The live-table-existence preflight is a SEPARATE concern ({@link
 * SchemaDropSnapshotRestorer#missingLiveTables}) that genuinely needs a connection; this class only
 * decides WHICH tables and in WHAT ORDER, never whether they are safe to write into.
 */
public final class SchemaDropSnapshotRestorePlan {

    private SchemaDropSnapshotRestorePlan() {
    }

    public record Plan(List<String> orderedTables, List<String> requestedButNotInSnapshot) {
    }

    /**
     * {@code requested} of {@code null}, empty, or a single {@code "all"} (case-insensitive) resolves
     * to every table {@code tablesInSnapshot} lists. Any other requested name absent from
     * {@code tablesInSnapshot} is reported in {@link Plan#requestedButNotInSnapshot()} and excluded
     * from the ordered list -- there is nothing captured to restore for it, so it cannot be given a
     * position; the caller decides whether that is a hard refusal.
     */
    public static Plan resolve(
            List<String> tablesInSnapshot,
            List<String> requested,
            Map<String, List<SchemaLifecycleExecutor.ForeignKeyDecl>> businessTableForeignKeys
    ) {
        Set<String> available = new LinkedHashSet<>(tablesInSnapshot);
        boolean wantsAll = requested == null || requested.isEmpty()
                || (requested.size() == 1 && "all".equalsIgnoreCase(requested.get(0)));

        List<String> wanted;
        List<String> missing;
        if (wantsAll) {
            wanted = new ArrayList<>(tablesInSnapshot);
            missing = List.of();
        } else {
            wanted = new ArrayList<>();
            missing = new ArrayList<>();
            for (String name : requested) {
                if (available.contains(name)) {
                    wanted.add(name);
                } else {
                    missing.add(name);
                }
            }
        }

        List<String> ordered = topologicalSort(wanted, businessTableForeignKeys == null ? Map.of() : businessTableForeignKeys);
        return new Plan(ordered, missing);
    }

    /**
     * Kahn's algorithm restricted to {@code tables}: a foreign key from {@code table} to a
     * {@code referencedTable} OUTSIDE this set is not an ordering constraint (the referenced table is
     * assumed already live, since restore never issues DDL and never creates tables). If the induced
     * subgraph does not reduce cleanly -- a cycle, not expected from a real FK graph but not asserted
     * against here either -- the remaining tables are appended in their original order rather than
     * this method throwing: restore is insert-only, so a sub-optimal order here is at worst a real FK
     * constraint violation the database itself reports for that one table (surfaced as a normal
     * restore failure), never silent data corruption.
     */
    private static List<String> topologicalSort(
            List<String> tables, Map<String, List<SchemaLifecycleExecutor.ForeignKeyDecl>> foreignKeys
    ) {
        Set<String> tableSet = new LinkedHashSet<>(tables);
        Map<String, Set<String>> dependsOn = new LinkedHashMap<>();
        for (String table : tables) {
            Set<String> parents = new LinkedHashSet<>();
            for (SchemaLifecycleExecutor.ForeignKeyDecl fk : foreignKeys.getOrDefault(table, List.of())) {
                String parent = fk.referencedTable();
                if (parent != null && tableSet.contains(parent) && !parent.equals(table)) {
                    parents.add(parent);
                }
            }
            dependsOn.put(table, parents);
        }

        List<String> ordered = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        boolean progressed = true;
        while (ordered.size() < tables.size() && progressed) {
            progressed = false;
            for (String table : tables) {
                if (placed.contains(table)) {
                    continue;
                }
                if (placed.containsAll(dependsOn.get(table))) {
                    ordered.add(table);
                    placed.add(table);
                    progressed = true;
                }
            }
        }
        for (String table : tables) {
            if (!placed.contains(table)) {
                ordered.add(table);
            }
        }
        return ordered;
    }
}
