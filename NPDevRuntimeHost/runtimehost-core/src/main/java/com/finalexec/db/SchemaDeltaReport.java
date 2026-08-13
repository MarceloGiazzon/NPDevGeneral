package com.finalexec.db;

import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropColumn;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropTable;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.NarrowType;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.Unknown;
import com.npdev.dsl.v1.schemaevolution.TypeChangeMatrix;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.finalexec.db.schemastate.SchemaDiffItem;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * LNCH-1 Phase 4 (task 4.1), rebuilt at SER-P4.2. Given the live database and the generation-time
 * {@link SchemaLifecycleExecutor.SchemaManifest}, itemizes the RESIDUAL diff that is left over once
 * Phases 1-3's rename/widening steps have already been attempted -- i.e. exactly the structural
 * differences that still make {@link SchemaLifecycleExecutor#classify} report
 * {@code TYPE_CHANGE_DETECTED} (unwidenable), {@code RENAME_DETECTED} (not fully explained), or
 * {@code DESTRUCTIVE}.
 *
 * <p><b>Single source of truth (SER-P4.2).</b> This report is now derived from the ONE canonical
 * desired-vs-current {@link SchemaDiff} (via {@link SchemaDiffEngine}) that {@code classify} and the
 * shadow parity probe also consume -- no second, independently-drifting copy of the live-diff logic.
 * The byte-identical acknowledgment token that {@link com.npdev.dsl.v1.schemaevolution.DestructiveAckToken}
 * hashes is guaranteed because the diff item's {@code itemKey} IS
 * {@link SchemaDeltaItem#stableString()} verbatim for the destructive kinds; this class only maps the
 * diff's items into the shared four-kind vocabulary and re-applies the report's own ownership / rename
 * exclusions. The equivalence to the former live-introspection itemizer was proven default-on across
 * the H2 + Postgres proof matrix at SER-P4.2a before that itemizer was retired here (P4.2b).
 *
 * <h2>Item vocabulary (exactly four kinds, per the plan) -- shared with the generator (Phase 6)</h2>
 * The item kinds themselves ({@link DropColumn}, {@link DropTable}, {@link NarrowType},
 * {@link Unknown}) and their {@code stableString()} format live in
 * {@link com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem} (DSL module), so that
 * {@code com.npdev.generator.schemaevolution.MigrationPlanEmitter}'s model-vs-model preview constructs
 * the IDENTICAL record types this class does, keeping the token byte-identical BY CONSTRUCTION.
 * <ul>
 *   <li>{@link DropColumn} -- a column present live but not in the manifest's expected set for that
 *       table, and not explained by any declared column rename.</li>
 *   <li>{@link DropTable} -- a live table the model no longer declares and that was not a declared
 *       concept (table) rename source; scoped to NPDev-owned tables only (LNCH-1-B7).</li>
 *   <li>{@link NarrowType} -- a shared column whose live SQL type changed in a way that is not a safe
 *       widening ({@link TypeChangeMatrix} {@code NARROWING} or {@code INCOMPARABLE}).</li>
 *   <li>{@link Unknown} -- a manifest-expected column that is neither live nor additive-eligible nor
 *       explained by a rename (a "new required field with no backfill support yet" case, Phase 5
 *       scope); or a live-database introspection failure.</li>
 * </ul>
 *
 * <h2>Stable, order-independent string form</h2>
 * Every item's {@code stableString()} is a plain, colon-joined {@code KIND:field:field:...} string
 * built ONLY from that item's own fields. {@link #generate} additionally sorts the full item LIST by a
 * deterministic key before returning, so two calls against the identical underlying diff produce
 * byte-identical {@link #items()} / {@link #stableStrings()} output regardless of manifest or JDBC
 * iteration order.
 */
final class SchemaDeltaReport {

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
        try (Connection connection = dataSource.getConnection()) {
            return new SchemaDeltaReport(fromDiff(connection, dataSource, manifest));
        } catch (Exception exception) {
            // Any live-introspection failure (including the unchecked wrapper CurrentSchemaReader throws)
            // becomes a single Unknown item, so the boot refuses with an itemized reason rather than crashing.
            List<SchemaDeltaItem> items = new ArrayList<>();
            items.add(new Unknown("Failed introspecting the live database while building the schema delta report: "
                    + exception.getMessage()));
            return new SchemaDeltaReport(List.copyOf(items));
        }
    }

    /** The exact {@link Unknown} description for a missing, non-additive, non-rename-explained column --
     * factored out so the generator-side preview (Phase 6) and this runtime itemizer emit the
     * byte-identical string that {@link com.npdev.dsl.v1.schemaevolution.DestructiveAckToken} hashes. */
    private static String unknownMissingColumnMessage(String table, String column) {
        return "Table '" + table + "' expects column '" + column
                + "' which is missing from the live database and is not explained by an "
                + "additive-eligible column or a declared rename -- likely a new required field "
                + "with no literal-default backfill declared (backfill support is LNCH-1 Phase 5 "
                + "scope, not yet available).";
    }

    /**
     * SER-P4.2: the residual destructive itemization, sourced from the canonical desired-vs-current
     * {@link SchemaDiff}. {@link ShadowParityProbe#scopeToOwnedBusinessTables} already removes internal
     * / {@code npdev_} / {@code flyway_schema_history} tables (which is why the former hardcoded
     * ALWAYS_EXCLUDED_TABLES list -- all {@code npdev_}-prefixed -- is no longer needed). The mapping:
     * DROP_COLUMN / NARROW_TYPE reuse the diff item's own key fields (its {@code itemKey} is already the
     * item's {@code stableString()} verbatim); DROP_TABLE re-applies the report's ownership gate
     * (LNCH-1-B7: only a table NPDev itself created is a drop candidate) and declared-rename-source
     * exclusion; a missing non-additive column (NEEDS_HOOK on an ADD) becomes the identical
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
                    // Never drop a declared rename source, and (when ownership is known) only a table
                    // NPDev itself created -- never one someone added by hand to the same schema.
                    if (renameOldTables.contains(table)) {
                        break;
                    }
                    if (owned != null && !owned.contains(table)) {
                        break;
                    }
                    items.add(new DropTable(di.table(), bestEffortRowCount(connection, di.table())));
                }
                case NEEDS_HOOK -> {
                    // Only a MISSING non-additive column is this report's Unknown; a NEEDS_HOOK on a
                    // shared-column tightening (TIGHTEN_NOT_NULL) is not part of the residual report.
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

    private static long bestEffortRowCount(Connection connection, String table) {
        try {
            String safeTable = SchemaLifecycleExecutor.quotedIdentifier(table);
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
