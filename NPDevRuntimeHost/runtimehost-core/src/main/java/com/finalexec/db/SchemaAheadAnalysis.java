package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentColumn;
import com.finalexec.db.schemastate.CurrentForeignKey;
import com.finalexec.db.schemastate.CurrentIndex;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentTable;
import com.finalexec.db.schemastate.CurrentUniqueConstraint;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.DesiredTable;
import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaAheadResolution;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.finalexec.db.schemastate.SchemaDiffItem;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * B5-A (boundary-lift 2026-09-02, package 2.3): the itemized diagnosis behind a B5 schema-ahead
 * refusal. Reuses the SAME {@link SchemaDiffEngine} and {@link SafetyClass} vocabulary every other
 * schema surface (the Impact Report, the rename suggester) already uses -- diffing THIS build's own
 * {@link DesiredSchema} against a {@link SchemaSnapshotStore} snapshot of the schema the fingerprint
 * the live database is actually at last recorded. Purely advisory: never runs DDL, never changes
 * whether the boot refuses (see {@code SchemaLifecycleExecutor}'s own B5 comment -- downgrade stays
 * unsupported). Degrades to a plain explanation, never throws, when no snapshot was ever recorded for
 * the ahead fingerprint (a database that reached it before this feature shipped, or via an operator
 * mark-done fast-forward -- neither path writes one).
 */
final class SchemaAheadAnalysis {

    private SchemaAheadAnalysis() {
    }

    /** One diff item plus the operator-facing bucket it falls into. */
    record ClassifiedItem(SchemaDiffItem item, SchemaAheadResolution resolution) {
    }

    /**
     * @return the itemized diff (this build's desired schema vs. the ahead fingerprint's own recorded
     *         snapshot), or empty when no snapshot was ever recorded for {@code aheadFingerprint}.
     */
    static Optional<List<ClassifiedItem>> classify(DataSource dataSource,
            SchemaLifecycleExecutor.SchemaManifest manifest, String aheadFingerprint) {
        Optional<DesiredSchema> aheadSnapshot = SchemaSnapshotStore.readSnapshot(dataSource, aheadFingerprint);
        if (aheadSnapshot.isEmpty()) {
            return Optional.empty();
        }
        DesiredSchema ourDesired = DesiredSchemaFactory.fromManifest(manifest);
        CurrentSchema aheadAsCurrent = asCurrentSchema(aheadSnapshot.get());
        SchemaDiff diff = new SchemaDiffEngine().diff(ourDesired, aheadAsCurrent);
        return Optional.of(diff.items().stream()
                .map(item -> new ClassifiedItem(item, resolutionFor(item)))
                .toList());
    }

    /**
     * Renders the diagnosis as the indented text block the boot refusal message and
     * {@code npdev db schema-ahead --report} both embed. Never throws -- a failure to compute the diff
     * degrades to an explanatory line, since this text only ever supplements a refusal that has already
     * decided to fire.
     */
    static String render(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest, String aheadFingerprint) {
        Optional<List<ClassifiedItem>> classified;
        try {
            classified = classify(dataSource, manifest, aheadFingerprint);
        } catch (RuntimeException failure) {
            return "  (could not compute the itemized diff: " + failure.getMessage() + ")";
        }
        if (classified.isEmpty()) {
            return "  (no schema snapshot was recorded for fingerprint " + aheadFingerprint + " -- it was "
                    + "either reached before this NPDev version started recording per-boot snapshots, or "
                    + "via an operator mark-done fast-forward, neither of which writes one. Forward-fix "
                    + "or restore a snapshot as described above; there is nothing more specific to show.)";
        }
        List<ClassifiedItem> items = classified.get();
        if (items.isEmpty()) {
            return "  (the live schema at " + aheadFingerprint + " and this build's own desired schema "
                    + "agree structurally -- the fingerprints differ for a reason the schema shape itself "
                    + "does not show, e.g. a metadata-only change.)";
        }
        StringBuilder text = new StringBuilder();
        for (ClassifiedItem classifiedItem : items) {
            SchemaDiffItem item = classifiedItem.item();
            text.append("  - ").append(describe(item)).append(" [").append(item.safetyClass()).append("] -> ")
                    .append(describeResolution(classifiedItem.resolution())).append('\n');
        }
        return text.substring(0, text.length() - 1);
    }

    /**
     * The bucket mapping (full reasoning in {@link SchemaAheadResolution}'s own javadoc): a destructive
     * item means reconciling toward this build's shape would destroy data the newer build added; a
     * missing-structure item (this build wants a table/column/wider type the live schema does not have)
     * means this build cannot function without the newer build; everything else -- the live schema
     * being a strict superset, or stricter about a constraint than this build needs -- is safe to boot
     * past unresolved.
     */
    static SchemaAheadResolution resolutionFor(SchemaDiffItem item) {
        if (item.isDestructive()) {
            return SchemaAheadResolution.NEEDS_DESTRUCTIVE_DOWNGRADE;
        }
        if (item.safetyClass() == SafetyClass.SAFE_RELAX) {
            return SchemaAheadResolution.PROCEED_IGNORING;
        }
        if (item.safetyClass() == SafetyClass.SAFE_ADDITIVE
                && (item.itemKey().startsWith("ADD_FOREIGN_KEY:") || item.itemKey().startsWith("ADD_INDEX:"))) {
            // SchemaDiffEngine's own javadoc: a missing FK/index is "advisory only ... never a gate,
            // never a drop" -- the same reasoning applies to a schema-ahead diagnosis.
            return SchemaAheadResolution.PROCEED_IGNORING;
        }
        return SchemaAheadResolution.NEEDS_NEWER_BUILD;
    }

    private static String describe(SchemaDiffItem item) {
        String location = item.column() != null ? item.table() + "." + item.column() : item.table();
        if (item.before() != null && item.after() != null) {
            return location + " (" + item.before() + " -> " + item.after() + ")";
        }
        if (item.after() != null) {
            return location + " (" + item.after() + ")";
        }
        return location;
    }

    private static String describeResolution(SchemaAheadResolution resolution) {
        return switch (resolution) {
            case PROCEED_IGNORING -> "this build can proceed ignoring it";
            case NEEDS_NEWER_BUILD -> "this needs the newer build";
            case NEEDS_DESTRUCTIVE_DOWNGRADE -> "this would require a destructive downgrade";
        };
    }

    /**
     * The newer build's stored {@link DesiredSchema}, reshaped as a {@link CurrentSchema} so the
     * EXISTING {@link SchemaDiffEngine#diff} can be reused unchanged (done-when #2: reuse the existing
     * {@code SafetyClass} vocabulary, never a parallel ladder). Every generated table's primary key is
     * the platform {@code id} column by convention -- {@link DesiredTable} carries no separate PK list
     * to convert, and the diff engine only reads {@code primaryKeyColumns()} to satisfy a declared
     * index over the same columns, so this is a safe, deliberate default rather than a real read.
     */
    private static CurrentSchema asCurrentSchema(DesiredSchema desired) {
        Map<String, CurrentTable> tables = new LinkedHashMap<>();
        for (DesiredTable table : desired.tables().values()) {
            Map<String, CurrentColumn> columns = new LinkedHashMap<>();
            for (var column : table.columns().values()) {
                columns.put(column.name(), new CurrentColumn(
                        column.name(), column.normalizedSqlType(), null, null, column.nullable(), null));
            }
            List<CurrentUniqueConstraint> uniques = table.uniques().stream()
                    .map(u -> new CurrentUniqueConstraint(null, u.columns()))
                    .toList();
            List<CurrentForeignKey> foreignKeys = table.foreignKeys().stream()
                    .map(fk -> new CurrentForeignKey(null, fk.columns(), fk.referencedTable(), fk.referencedColumns(), null))
                    .toList();
            List<CurrentIndex> indexes = table.indexes().stream()
                    .map(ix -> new CurrentIndex(null, ix.columns(), ix.unique()))
                    .toList();
            tables.put(table.name(), new CurrentTable(table.name(), columns, List.of("id"), uniques, foreignKeys, indexes));
        }
        return new CurrentSchema(tables);
    }
}
