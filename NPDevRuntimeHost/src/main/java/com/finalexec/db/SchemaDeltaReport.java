package com.finalexec.db;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LNCH-1 Phase 4 (task 4.1). Given the live database and the generation-time
 * {@link SchemaLifecycleExecutor.SchemaManifest}, itemizes the RESIDUAL diff that is left over
 * once Phases 1-3's rename/widening steps have already been attempted -- i.e. exactly the
 * structural differences that still make {@link SchemaLifecycleExecutor#classify} report
 * {@code TYPE_CHANGE_DETECTED} (unwidenable), {@code RENAME_DETECTED} (not fully explained), or
 * {@code DESTRUCTIVE}. Reuses the SAME live-introspection primitives the executor's own rename/
 * widening/classification code already uses ({@link SchemaLifecycleExecutor#readActualTableNames},
 * {@link SchemaLifecycleExecutor#readActualColumns}, {@link SchemaLifecycleExecutor#readActualColumnTypes},
 * {@link SchemaLifecycleExecutor#normalizeSqlType}) -- no second, independently-drifting copy of
 * that logic.
 *
 * <h2>Item vocabulary (exactly four kinds, per the plan)</h2>
 * <ul>
 *   <li>{@link DropColumn} -- a column present live but not in the manifest's expected set for
 *       that table, and not explained by any declared column rename (per {@link RenameResolution}).</li>
 *   <li>{@link DropTable} -- a live table not in the manifest's business/internal table sets, and
 *       not explained by a declared concept (table) rename.</li>
 *   <li>{@link NarrowType} -- a shared column (same name, present both live and expected) whose
 *       live SQL type does not match the manifest's declared type, and
 *       {@link TypeChangeMatrix#classify(String, String)} returns {@code NARROWING} or
 *       {@code INCOMPARABLE} for the (actual -&gt; expected) pair. Named {@code NARROW_TYPE} even
 *       for the {@code INCOMPARABLE} case -- see the class-level note on {@link NarrowType}.</li>
 *   <li>{@link Unknown} -- anything this class cannot cleanly attribute to one of the three named
 *       kinds above (today: a manifest-expected column that is neither live nor additive-eligible
 *       nor explained by a rename -- a "new required field with no backfill support yet" case that
 *       is Phase 5 scope, not Phase 4's; a live-database introspection failure).</li>
 * </ul>
 *
 * <h2>Stable, order-independent string form</h2>
 * Every item's {@link Item#stableString()} is a plain, colon-joined {@code KIND:field:field:...}
 * string built ONLY from that item's own fields -- never from iteration order. {@link #generate}
 * additionally sorts the full item LIST by a deterministic key (table, then column/secondary key,
 * then kind, then the stable string itself as a final tiebreaker) before returning, so two calls
 * against the identical underlying diff produce byte-identical {@link #items()} / {@link #stableStrings()}
 * output regardless of which {@code Map}/{@code Set} implementation or iteration order the manifest
 * or JDBC driver happened to hand back. This is what lets {@link com.npdev.dsl.v1.schemaevolution.DestructiveAckToken}
 * hash the item set deterministically without itself needing any table/column-aware sorting logic.
 */
final class SchemaDeltaReport {

    /**
     * Self-bootstrapped npdev tables that must never be treated as drop candidates. In a REAL
     * generated app, {@code npdev_schema_metadata} is normally also listed in
     * {@code manifest.internalTables()} (via the generator's {@code NpdevInternalTables} catalog),
     * which alone would already protect it -- but that is a SINGLE point of protection this class
     * does not want to depend on exclusively: a hand-built manifest (every test in this package
     * does this; also a plausible future generator regression) that simply omits
     * {@code internalTables} must not be able to turn "the app's own fingerprint bookkeeping table"
     * into a surgical drop candidate. {@code npdev_schema_history} (this phase's own audit table)
     * is never part of that catalog at all (by design -- see the class-level note on
     * {@code SchemaLifecycleExecutor}'s {@code HISTORY_TABLE} constant), so it needs the same
     * hardcoded protection unconditionally. {@code flyway_schema_history} is Flyway's own
     * bookkeeping table, never NPDev-model-owned.
     */
    private static final Set<String> ALWAYS_EXCLUDED_TABLES =
            Set.of("flyway_schema_history", "npdev_schema_history", "npdev_schema_metadata");

    private final List<Item> items;

    private SchemaDeltaReport(List<Item> items) {
        this.items = items;
    }

    List<Item> items() {
        return items;
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    /** True when every item is one of the three named, surgically-executable kinds -- i.e. no
     * {@link Unknown} item is present. This is exactly the gate {@code SchemaLifecycleExecutor}
     * uses to decide surgical-vs-whole-schema execution (task 4.3). */
    boolean hasOnlyNamedDestructiveKinds() {
        for (Item item : items) {
            if (item instanceof Unknown) {
                return false;
            }
        }
        return true;
    }

    /** Every table touched by at least one item, sorted -- used to scope the surgical path's
     * pre-drop snapshot and DDL to only the tables actually implicated by this report. */
    Set<String> affectedTables() {
        Set<String> tables = new LinkedHashSet<>();
        for (Item item : items) {
            String table = item.table();
            if (table != null && !table.isBlank()) {
                tables.add(table);
            }
        }
        return tables;
    }

    /** Every item's {@link Item#stableString()}, in this report's already-deterministic order --
     * the exact input {@link com.npdev.dsl.v1.schemaevolution.DestructiveAckToken#compute} hashes. */
    List<String> stableStrings() {
        List<String> strings = new ArrayList<>();
        for (Item item : items) {
            strings.add(item.stableString());
        }
        return strings;
    }

    static SchemaDeltaReport generate(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        List<Item> items = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();

            itemizeTableLevelDiff(connection, metadata, manifest, items);
            itemizeColumnLevelDiff(metadata, manifest, items);
        } catch (SQLException exception) {
            items.add(new Unknown("Failed introspecting the live database while building the schema delta report: "
                    + exception.getMessage()));
        }

        items.sort(Comparator.comparing(SchemaDeltaReport::sortKey));
        return new SchemaDeltaReport(List.copyOf(items));
    }

    private static void itemizeTableLevelDiff(
            Connection connection,
            DatabaseMetaData metadata,
            SchemaLifecycleExecutor.SchemaManifest manifest,
            List<Item> items
    ) throws SQLException {
        Set<String> expectedTables = new LinkedHashSet<>(manifest.businessTableColumns().keySet());
        expectedTables.addAll(manifest.internalTables());
        expectedTables.addAll(ALWAYS_EXCLUDED_TABLES);
        Set<String> liveTables = SchemaLifecycleExecutor.readActualTableNames(metadata);

        // The OLD side of a declared table rename is legitimately "extra" mid-migration, but
        // attemptInPlaceTableRenames() (unconditional, always attempted before classify() ever
        // runs -- see beforeMigrate()) will already have renamed it away by the time this report
        // is generated. If it is STILL live under the old name here, either the rename target
        // itself already exists too (a genuine conflict, not a clean drop) or something else is
        // odd -- either way, not confidently a DROP_TABLE candidate, so it is excluded rather than
        // risk surgically destroying data that a declared rename says should have been preserved.
        Set<String> renameOldTableNames = new LinkedHashSet<>(manifest.businessTableRenames().values());

        for (String table : liveTables) {
            if (expectedTables.contains(table) || renameOldTableNames.contains(table)) {
                continue;
            }
            items.add(new DropTable(table, bestEffortRowCount(connection, table)));
        }
    }

    private static void itemizeColumnLevelDiff(
            DatabaseMetaData metadata,
            SchemaLifecycleExecutor.SchemaManifest manifest,
            List<Item> items
    ) throws SQLException {
        for (Map.Entry<String, List<String>> entry : manifest.businessTableColumns().entrySet()) {
            String table = entry.getKey();
            List<String> expectedColumns = entry.getValue();
            Set<String> actualColumns = SchemaLifecycleExecutor.readActualColumns(metadata, table);
            if (actualColumns.isEmpty()) {
                continue; // brand-new table (not yet created) -- nothing to itemize here
            }
            Set<String> expected = new LinkedHashSet<>(expectedColumns);
            Set<String> extraInDb = new LinkedHashSet<>(actualColumns);
            extraInDb.removeAll(expected);
            Set<String> missingInDb = new LinkedHashSet<>(expected);
            missingInDb.removeAll(actualColumns);

            Map<String, String> declaredRenames = manifest.businessTableRenamedColumns().getOrDefault(table, Map.of());
            RenameResolution.Result resolution = RenameResolution.resolve(missingInDb, extraInDb, declaredRenames);

            Map<String, String> actualTypes = SchemaLifecycleExecutor.readActualColumnTypes(metadata, table);
            for (String extraColumn : resolution.remainingExtra()) {
                items.add(new DropColumn(table, extraColumn, normalizedOrRaw(actualTypes.get(extraColumn))));
            }

            Set<String> additiveEligible = new LinkedHashSet<>(
                    manifest.businessTableAdditiveColumns().getOrDefault(table, List.of()));
            for (String missingColumn : resolution.remainingMissing()) {
                if (additiveEligible.contains(missingColumn)) {
                    continue; // handled by the ordinary additive repeatable migration, not this report
                }
                items.add(new Unknown("Table '" + table + "' expects column '" + missingColumn
                        + "' which is missing from the live database and is not explained by an "
                        + "additive-eligible column or a declared rename -- likely a new required field "
                        + "with no literal-default backfill declared (backfill support is LNCH-1 Phase 5 "
                        + "scope, not yet available)."));
            }

            Map<String, String> expectedTypes = manifest.businessTableColumnTypes().getOrDefault(table, Map.of());
            Set<String> sharedColumns = new LinkedHashSet<>(expected);
            sharedColumns.retainAll(actualColumns);
            for (String column : sharedColumns) {
                String expectedType = expectedTypes.get(column);
                String actualType = actualTypes.get(column);
                if (expectedType == null || actualType == null) {
                    continue;
                }
                String normalizedExpected = SchemaLifecycleExecutor.normalizeSqlType(expectedType);
                String normalizedActual = SchemaLifecycleExecutor.normalizeSqlType(actualType);
                if (normalizedExpected == null || normalizedActual == null || normalizedExpected.equals(normalizedActual)) {
                    continue;
                }
                TypeChangeMatrix.Classification classification = TypeChangeMatrix.classify(actualType, expectedType);
                if (classification == TypeChangeMatrix.Classification.NARROWING
                        || classification == TypeChangeMatrix.Classification.INCOMPARABLE) {
                    items.add(new NarrowType(table, column, normalizedOrRaw(actualType), normalizedOrRaw(expectedType)));
                }
                // A residual WIDENING here would mean attemptInPlaceTypeWidenings() should already
                // have resolved it before this report ever runs; per-table all-or-nothing means that
                // can only happen if this exact column's diff was blocked by a sibling NARROWING/
                // INCOMPARABLE column on the same table -- which IS itemized (as NARROW_TYPE, above,
                // for that sibling), so the table as a whole is still correctly represented.
            }
        }
    }

    /**
     * The item vocabulary's type fields use {@link SchemaLifecycleExecutor#normalizeSqlType}'s
     * canonical form (e.g. {@code "VARCHAR(50)"}), not the engine-raw JDBC type name -- H2 reports
     * {@code TYPE_NAME = "CHARACTER VARYING"} for a column declared {@code VARCHAR(n)} (see
     * {@code normalizeSqlType}'s own javadoc for the confirmed-empirical detail), which would
     * otherwise make an item's stable string form (and therefore the acknowledgment token) depend
     * on which JDBC driver's raw vocabulary happened to run the introspection, not just on the
     * logical type change being described. Falls back to the raw string only in the
     * (unreachable-in-practice) case that normalization itself returns {@code null}.
     */
    private static String normalizedOrRaw(String sqlType) {
        String normalized = SchemaLifecycleExecutor.normalizeSqlType(sqlType);
        return normalized != null ? normalized : (sqlType == null ? "" : sqlType);
    }

    private static long bestEffortRowCount(Connection connection, String table) {
        try {
            String safeTable = SchemaLifecycleExecutor.safeIdentifier(table);
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + safeTable);
                 ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : -1L;
            }
        } catch (Exception exception) {
            return -1L; // sentinel: row count unreadable -- documented on DropTable
        }
    }

    /** Deterministic sort key: table, then a kind-specific secondary key (column, where one
     * exists), then the kind name, then the full stable string as a final tiebreaker -- so the
     * overall item order (and therefore {@link #stableStrings()}) never depends on live-DB or
     * manifest iteration order. */
    private static String sortKey(Item item) {
        String table = item.table() == null ? "" : item.table();
        String secondary = "";
        if (item instanceof DropColumn dropColumn) {
            secondary = dropColumn.column();
        } else if (item instanceof NarrowType narrowType) {
            secondary = narrowType.column();
        }
        String kind = item.getClass().getSimpleName();
        return table + ' ' + secondary + ' ' + kind + ' ' + item.stableString();
    }

    /** Common surface every item kind implements. Deliberately a plain (non-sealed) interface --
     * this project targets Java 17 without preview features, and sealed-type switch pattern
     * matching is a Java 21 feature; {@code instanceof} pattern matching (used throughout this
     * class and its caller) is fully available on 17. */
    interface Item {
        /** The table this item concerns, or {@code ""} if not applicable (only {@link Unknown}). */
        String table();

        /** A stable string form built only from this item's own fields -- never from iteration
         * order -- suitable for hashing (via {@link com.npdev.dsl.v1.schemaevolution.DestructiveAckToken})
         * and for JSON/log/history-row serialization. */
        String stableString();
    }

    record DropColumn(String table, String column, String sqlType) implements Item {
        @Override
        public String stableString() {
            return "DROP_COLUMN:" + table + ":" + column + ":" + (sqlType == null ? "" : sqlType);
        }
    }

    record DropTable(String table, long rowCountAtClassification) implements Item {
        @Override
        public String stableString() {
            return "DROP_TABLE:" + table + ":" + rowCountAtClassification;
        }
    }

    /**
     * A shared column whose live type doesn't match the declared type, where
     * {@link TypeChangeMatrix#classify(String, String)} returned {@code NARROWING} OR
     * {@code INCOMPARABLE} for the (actual -&gt; expected) pair. Named {@code NARROW_TYPE} for
     * BOTH cases, per the plan's exact item vocabulary (it lists only four kinds, not five) --
     * {@code INCOMPARABLE} (e.g. a wholesale type-family mismatch such as VARCHAR -&gt; INTEGER)
     * is still, in practice, "a type on this column narrowed/changed in a way that isn't a safe
     * widening", which is exactly what {@code NARROW_TYPE} communicates to an operator deciding
     * whether to acknowledge it; a separate {@code INCOMPARABLE_TYPE} item kind would be a
     * distinction without a difference for this phase's surgical-execution purposes (drop-and-
     * recreate-column is the correct DDL response to both cases identically).
     */
    record NarrowType(String table, String column, String fromType, String toType) implements Item {
        @Override
        public String stableString() {
            return "NARROW_TYPE:" + table + ":" + column + ":" + fromType + ":" + toType;
        }
    }

    record Unknown(String description) implements Item {
        @Override
        public String table() {
            return "";
        }

        @Override
        public String stableString() {
            return "UNKNOWN:" + description;
        }
    }
}
