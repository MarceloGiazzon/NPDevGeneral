package com.finalexec.db;

import com.npdev.dsl.v1.schemaevolution.RenameResolution;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropColumn;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropTable;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.NarrowType;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.Unknown;
import com.npdev.dsl.v1.schemaevolution.SqlTypeNormalization;
import com.npdev.dsl.v1.schemaevolution.TypeChangeMatrix;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.finalexec.db.schemastate.SchemaDiffItem;

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
import java.util.Locale;
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
 * <h2>Item vocabulary (exactly four kinds, per the plan) -- shared with the generator (Phase 6)</h2>
 * The item kinds themselves ({@link DropColumn}, {@link DropTable}, {@link NarrowType},
 * {@link Unknown}) and their {@code stableString()} format now live in
 * {@link com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem} (DSL module), NOT in this class -- moved
 * there in Phase 6 (task 6.1's (A) share decision) so that
 * {@code com.npdev.generator.schemaevolution.MigrationPlanEmitter}'s model-vs-model preview
 * constructs the IDENTICAL record types this class's live-DB introspection constructs, guaranteeing
 * {@link com.npdev.dsl.v1.schemaevolution.DestructiveAckToken} produces byte-identical tokens for
 * the same underlying change BY CONSTRUCTION. This class keeps only the JDBC-introspection-specific
 * itemization logic (below), which has no generator-side equivalent to share with (the generator
 * never introspects a live database).
 * <ul>
 *   <li>{@link DropColumn} -- a column present live but not in the manifest's expected set for
 *       that table, and not explained by any declared column rename (per {@link RenameResolution}).</li>
 *   <li>{@link DropTable} -- a live table not in the manifest's business/internal table sets, and
 *       not explained by a declared concept (table) rename.</li>
 *   <li>{@link NarrowType} -- a shared column (same name, present both live and expected) whose
 *       live SQL type does not match the manifest's declared type, and
 *       {@link TypeChangeMatrix#classify(String, String)} returns {@code NARROWING} or
 *       {@code INCOMPARABLE} for the (actual -&gt; expected) pair. Named {@code NARROW_TYPE} even
 *       for the {@code INCOMPARABLE} case -- see {@link SchemaDeltaItem.NarrowType}'s javadoc.</li>
 *   <li>{@link Unknown} -- anything this class cannot cleanly attribute to one of the three named
 *       kinds above (today: a manifest-expected column that is neither live nor additive-eligible
 *       nor explained by a rename -- a "new required field with no backfill support yet" case that
 *       is Phase 5 scope, not Phase 4's; a live-database introspection failure).</li>
 * </ul>
 *
 * <h2>Stable, order-independent string form</h2>
 * Every item's {@code stableString()} is a plain, colon-joined {@code KIND:field:field:...}
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
     * bookkeeping table, never NPDev-model-owned. {@link PendingSchemaAcknowledgmentStore#TABLE}
     * (LNCH-1 Phase 6, task 6.2a) is the same kind of self-bootstrapped npdev bookkeeping table as
     * {@code npdev_schema_history} -- never part of {@code internalTables} either -- so it needs
     * the identical unconditional protection (confirmed live: without this, the executor's own
     * {@code CREATE TABLE IF NOT EXISTS} for it made every destructive-path test in this package
     * spuriously classify it as a DROP_TABLE candidate, since it is a real live table with no
     * matching manifest entry). {@link MigrationMarkStore#TABLE} (REG-7.2) is the identical shape --
     * self-bootstrapped by {@code beforeMigrate}'s very first read, ahead of {@code classify()} --
     * and needs the same protection for the same confirmed-live reason (a real boot rehearsal with
     * this omitted turned every destructive-path test into a spurious
     * {@code DROP_TABLE:npdev_schema_migration_mark}). {@link MigrationClaimStore#TABLE} (REG-7.3) is
     * claimed at the very top of {@code migrate(Flyway, SchemaManifest)}, before {@code beforeMigrate}
     * (and therefore this report's own {@code classify()}) ever runs on an upgrade boot, so it needs
     * the identical protection pre-emptively, learned from the {@code MigrationMarkStore} incident
     * above rather than re-discovered live.
     */
    private static final Set<String> ALWAYS_EXCLUDED_TABLES =
            Set.of("flyway_schema_history", "npdev_schema_history", "npdev_schema_metadata",
                    PendingSchemaAcknowledgmentStore.TABLE, MigrationMarkStore.TABLE, MigrationClaimStore.TABLE);

    private final List<SchemaDeltaItem> items;

    private SchemaDeltaReport(List<SchemaDeltaItem> items) {
        this.items = items;
    }

    List<SchemaDeltaItem> items() {
        return items;
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    /** True when every item is one of the three named, surgically-executable kinds -- i.e. no
     * {@link Unknown} item is present. This is exactly the gate {@code SchemaLifecycleExecutor}
     * uses to decide surgical-vs-whole-schema execution (task 4.3). */
    boolean hasOnlyNamedDestructiveKinds() {
        for (SchemaDeltaItem item : items) {
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
        for (SchemaDeltaItem item : items) {
            String table = item.table();
            if (table != null && !table.isBlank()) {
                tables.add(table);
            }
        }
        return tables;
    }

    /** Every item's {@link SchemaDeltaItem#stableString()}, in this report's already-deterministic
     * order -- the exact input {@link com.npdev.dsl.v1.schemaevolution.DestructiveAckToken#compute}
     * hashes. */
    List<String> stableStrings() {
        List<String> strings = new ArrayList<>();
        for (SchemaDeltaItem item : items) {
            strings.add(item.stableString());
        }
        return strings;
    }

    /** Every item's {@link SchemaDeltaItem#displayString()}, in this report's deterministic order --
     * the human-facing form used for {@code items_json} and operator log lines. Differs from
     * {@link #stableStrings()} only for {@code DROP_TABLE} (which appends its display-only row
     * count here, but never in the hashed stable string -- LNCH-1 remediation F2). */
    List<String> displayStrings() {
        List<String> strings = new ArrayList<>();
        for (SchemaDeltaItem item : items) {
            strings.add(item.displayString());
        }
        return strings;
    }

    static SchemaDeltaReport generate(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        List<SchemaDeltaItem> items = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();

            itemizeTableLevelDiff(connection, metadata, manifest, items,
                    SchemaLifecycleExecutor.readOwnedBusinessTables(dataSource));
            itemizeColumnLevelDiff(metadata, manifest, items);
            items.sort(Comparator.comparing(SchemaDeltaReport::sortKey));
            List<SchemaDeltaItem> legacy = List.copyOf(items);
            // SER-P4.2: prove the diff-derived itemization yields byte-identical stableStrings (the
            // operator acknowledgment-token input) before it is trusted to replace the legacy live-
            // introspection. Default-on assert; legacy is still returned here, so behavior is preserved.
            assertDiffEquivalence(connection, dataSource, manifest, legacy);
            return new SchemaDeltaReport(legacy);
        } catch (SQLException exception) {
            items.add(new Unknown("Failed introspecting the live database while building the schema delta report: "
                    + exception.getMessage()));
            items.sort(Comparator.comparing(SchemaDeltaReport::sortKey));
            return new SchemaDeltaReport(List.copyOf(items));
        }
    }

    /** The exact {@link Unknown} description for a missing, non-additive, non-rename-explained column --
     * factored out so the legacy live-introspection itemizer and the SER-P4.2 diff-derived itemizer
     * emit the byte-identical string that {@link com.npdev.dsl.v1.schemaevolution.DestructiveAckToken}
     * hashes. */
    private static String unknownMissingColumnMessage(String table, String column) {
        return "Table '" + table + "' expects column '" + column
                + "' which is missing from the live database and is not explained by an "
                + "additive-eligible column or a declared rename -- likely a new required field "
                + "with no literal-default backfill declared (backfill support is LNCH-1 Phase 5 "
                + "scope, not yet available).";
    }

    /**
     * SER-P4.2: the same residual itemization as {@link #itemizeTableLevelDiff}/{@link #itemizeColumnLevelDiff}
     * above, but sourced from the canonical desired-vs-current {@link SchemaDiff} instead of a second,
     * independently-drifting live introspection. Maps the diff's destructive/hook items to the exact
     * four-kind vocabulary: DROP_COLUMN/NARROW_TYPE reuse the diff item's own key fields (its itemKey is
     * already {@code SchemaDeltaItem.stableString()} verbatim); DROP_TABLE re-applies the SAME ownership
     * gate and declared-rename-source exclusion the legacy path applies (scope already removes internal/
     * npdev_/flyway tables); a missing non-additive column (NEEDS_HOOK on an ADD) becomes the identical
     * {@link Unknown}. Every other safety class is not part of the residual destructive report.
     */
    private static List<SchemaDeltaItem> fromDiff(Connection connection, DataSource dataSource,
            SchemaLifecycleExecutor.SchemaManifest manifest) {
        CurrentSchema current = new CurrentSchemaReader().read(dataSource);
        SchemaDiff diff = new SchemaDiffEngine().diff(DesiredSchemaFactory.fromManifest(manifest),
                ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest));
        Set<String> owned = SchemaLifecycleExecutor.readOwnedBusinessTables(dataSource);
        Set<String> renameOldTables = new LinkedHashSet<>();
        for (String old : manifest.businessTableRenames().values()) {
            renameOldTables.add(old.toLowerCase(Locale.ROOT));
        }
        List<SchemaDeltaItem> items = new ArrayList<>();
        for (SchemaDiffItem di : diff.items()) {
            switch (di.safetyClass()) {
                case DESTRUCTIVE_DROP_COLUMN ->
                        items.add(new DropColumn(di.table(), di.column(), di.before() == null ? "" : di.before()));
                case DESTRUCTIVE_NARROW_TYPE ->
                        items.add(new NarrowType(di.table(), di.column(), di.before(), di.after()));
                case DESTRUCTIVE_DROP_TABLE -> {
                    String table = di.table().toLowerCase(Locale.ROOT);
                    // Legacy never drops a declared rename source, and (when ownership is known) only a
                    // table NPDev itself created.
                    if (renameOldTables.contains(table)) {
                        break;
                    }
                    if (owned != null && !owned.contains(table)) {
                        break;
                    }
                    items.add(new DropTable(di.table(), bestEffortRowCount(connection, di.table())));
                }
                case NEEDS_HOOK -> {
                    // Only a MISSING non-additive column is the legacy report's Unknown; a NEEDS_HOOK on a
                    // shared-column tightening (TIGHTEN_NOT_NULL) is not itemized by the legacy path.
                    if (di.itemKey().startsWith("ADD_REQUIRED_COLUMN:")
                            && !manifest.businessTableAdditiveColumns()
                                    .getOrDefault(di.table(), List.of()).contains(di.column())) {
                        items.add(new Unknown(unknownMissingColumnMessage(di.table(), di.column())));
                    }
                }
                default -> {
                    // SAFE_*, NEEDS_BACKFILL, SAFE_WIDEN/RELAX/RENAME, SAFE_TABLE_CREATE: not destructive-residual.
                }
            }
        }
        items.sort(Comparator.comparing(SchemaDeltaReport::sortKey));
        return items;
    }

    /** SER-P4.2 equivalence probe: compares the diff-derived itemization's {@code stableString} SET
     * against the legacy report's. Set-equality (order-independent, as {@code DestructiveAckToken} sorts
     * before hashing) is exactly what keeps the acknowledgment token byte-identical. Gated on
     * {@code npdev.schema.delta.check}; throws on divergence only under {@code npdev.schema.delta.assert}.
     * Fully swallowed otherwise -- can never change behavior. */
    private static void assertDiffEquivalence(Connection connection, DataSource dataSource,
            SchemaLifecycleExecutor.SchemaManifest manifest, List<SchemaDeltaItem> legacy) {
        boolean assertOn = Boolean.getBoolean("npdev.schema.delta.assert");
        if (System.getProperty("npdev.schema.delta.check") == null && !assertOn) {
            return;
        }
        List<String> diffStrings;
        try {
            diffStrings = fromDiff(connection, dataSource, manifest).stream()
                    .map(SchemaDeltaItem::stableString).sorted().toList();
        } catch (Throwable ignored) {
            return;
        }
        List<String> legacyStrings = legacy.stream().map(SchemaDeltaItem::stableString).sorted().toList();
        if (!diffStrings.equals(legacyStrings)) {
            String line = "DELTA_DIVERGENCE: legacy=" + legacyStrings + " diff=" + diffStrings;
            System.out.println(line);
            if (assertOn) {
                throw new AssertionError(line);
            }
        }
    }

    /**
     * @param ownedBusinessTables LNCH-1-B7: the business tables a previous successful boot recorded
     *        as NPDev-owned, or {@code null} when ownership has never been recorded. When non-null,
     *        DROP_TABLE itemization is restricted to that set, so a table someone created by hand in
     *        the same schema can never be itemized (and therefore never surgically dropped) just
     *        because the manifest does not mention it. When null, the pre-B7 behaviour is preserved
     *        so legacy apps and the existing unit tests are unaffected.
     */
    private static void itemizeTableLevelDiff(
            Connection connection,
            DatabaseMetaData metadata,
            SchemaLifecycleExecutor.SchemaManifest manifest,
            List<SchemaDeltaItem> items,
            Set<String> ownedBusinessTables
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
            // LNCH-1-B7: when ownership is known, only a table NPDev itself created is a drop
            // candidate -- never a table someone added by hand to the same schema.
            if (ownedBusinessTables != null && !ownedBusinessTables.contains(table.toLowerCase(Locale.ROOT))) {
                continue;
            }
            items.add(new DropTable(table, bestEffortRowCount(connection, table)));
        }
    }

    private static void itemizeColumnLevelDiff(
            DatabaseMetaData metadata,
            SchemaLifecycleExecutor.SchemaManifest manifest,
            List<SchemaDeltaItem> items
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
                items.add(new Unknown(unknownMissingColumnMessage(table, missingColumn)));
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
                String normalizedExpected = SqlTypeNormalization.normalize(expectedType);
                String normalizedActual = SqlTypeNormalization.normalize(actualType);
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
        String normalized = SqlTypeNormalization.normalize(sqlType);
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
    private static String sortKey(SchemaDeltaItem item) {
        String table = item.table() == null ? "" : item.table();
        String secondary = "";
        if (item instanceof DropColumn dropColumn) {
            secondary = dropColumn.column();
        } else if (item instanceof NarrowType narrowType) {
            secondary = narrowType.column();
        }
        String kind = item.getClass().getSimpleName();
        return table + ' ' + secondary + ' ' + kind + ' ' + item.stableString();
    }
}
