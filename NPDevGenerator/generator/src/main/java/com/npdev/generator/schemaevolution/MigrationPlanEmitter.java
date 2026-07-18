package com.npdev.generator.schemaevolution;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import com.npdev.dsl.v1.schemaevolution.RenameResolution;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import com.npdev.dsl.v1.schemaevolution.TypeChangeMatrix;
import com.npdev.generator.dbconfig.GeneratedDatabasePlan;
import com.npdev.generator.dbconfig.SchemaRealizationEmitter;
import com.npdev.generator.dbconfig.SchemaRealizationEmitter.BusinessTableMetadata;
import com.npdev.generator.dbconfig.SchemaRealizationEmitter.UniqueConstraintDecl;
import com.npdev.generator.dbconfig.UserDatabaseDefinition;
import com.npdev.generator.dbconfig.UserDatabaseDefinitionLoader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * LNCH-1 Phase 6 (task 6.1). Computes a {@link MigrationPlan} -- a PREVIEW of what a future app
 * boot's {@code com.finalexec.db.SchemaLifecycleExecutor} would do to an already-deployed app's
 * database -- by diffing the NEW compiled model against the PREVIOUS one directly (no live database
 * access; the generator never has one). Pure, fully-unit-testable: {@link #compute} takes only
 * already-in-memory objects and returns a value, no I/O.
 *
 * <h2>Design decision: (A) share, not (B) duplicate</h2>
 * This class deliberately reuses, rather than re-derives, three things that already exist:
 * <ol>
 *   <li>The destructive item vocabulary and its {@code stableString()} format
 *       ({@link SchemaDeltaItem}), the widening/narrowing classifier ({@link TypeChangeMatrix}),
 *       and the missing/extra-set rename resolver ({@link RenameResolution}) -- all three moved to
 *       {@code com.npdev.dsl.v1.schemaevolution} (the DSL module) in this same phase specifically so
 *       this class and {@code com.finalexec.db.SchemaDeltaReport} (RuntimeHost) construct the
 *       IDENTICAL record types and call the IDENTICAL {@code stableString()} implementations. This
     * guarantees {@link DestructiveAckToken#compute} produces byte-identical tokens for the same
     * underlying change BY CONSTRUCTION -- the exact "two independent derivations that must agree"
     * property §2.3 of {@code docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md} requires. No circular-dependency
     * blocker existed: the DSL module has zero dependency on the generator or RuntimeHost.</li>
 *   <li>The per-concept manifest-shaped metadata computation (columns, additive-eligibility,
 *       renames, required/default-literal backfill shape, unique constraints) --
 *       {@link SchemaRealizationEmitter#computeBusinessTableMetadata}, extracted from
 *       {@code SchemaRealizationEmitter#emitManifest}'s own inline loop in this same phase. There is
 *       no RuntimeHost equivalent to share via the DSL module for THIS piece (RuntimeHost never
 *       computes it FROM a model; it only ever reads the manifest this method feeds) -- so "reuse"
 *       here means calling the actual production method with a different {@link CompiledModel},
 *       once for the previous model and once for the new one, never hand-reimplementing
 *       additive-eligibility or unique-constraint determination.</li>
 *   <li>The schema-fingerprint algorithm
 *       ({@link UserDatabaseDefinitionLoader#computeSchemaFingerprint}), extracted from
 *       {@code UserDatabaseDefinitionLoader#load}'s own inline expression in this same phase, so
 *       {@link #compute}'s {@code fromFingerprint} is produced by the exact same hash algorithm as
 *       {@code toFingerprint} (which is simply {@link GeneratedDatabasePlan#schemaFingerprint()},
 *       the ALREADY-computed production value for the new model -- never re-derived).</li>
 * </ol>
 *
 * <h2>Known, documented fidelity limitation: DROP_TABLE row counts</h2>
 * A {@code DROP_TABLE} item's stable string includes a row count
 * ({@code "DROP_TABLE:" + table + ":" + rowCount}, see {@link SchemaDeltaItem.DropTable}). The
 * runtime executor reads this from the LIVE database at boot; this class has no live database to
 * introspect, so it always uses {@code -1} (the same "unreadable/unknown" sentinel
 * {@code SchemaDeltaReport#bestEffortRowCount} already falls back to on a read failure). This means
 * a plan's {@code destructiveAckToken} for a change that includes a DROP_TABLE will generally NOT
 * byte-match what the executor computes at boot (which uses the real row count) -- this is expected
 * and consistent with the plan being a preview, not an authority (§2.3): the executor's own
 * re-derivation is always the actual gate, and task 6.3's "friendlier" agreement-check message
 * exists precisely to surface this kind of plan-vs-boot drift clearly instead of an opaque refusal.
 * Every other destructive kind (DROP_COLUMN, NARROW_TYPE, and the UNKNOWN cases this class emits)
 * is fully derivable from the model diff alone, so their stable strings DO match exactly.
 *
 * <h2>Known, documented limitation: some UNKNOWN items cannot be unblocked by any token</h2>
 * A required field with no literal default, or a required (non-many-to-many) bond field added to
 * an existing table, are both classified here as {@code UNKNOWN} destructive items (matching
 * {@link SchemaDeltaItem.Unknown}'s vocabulary) -- but in the CURRENT executor
 * ({@code applyRequiredFieldBackfills} / {@code refuseIfRequiredBondColumnMissing}, LNCH-1 Phase 5),
 * both cases are intercepted with their OWN unconditional refusal BEFORE
 * {@code SchemaDeltaReport}/the token mechanism is ever consulted -- no acknowledgment token
 * bypasses them, only a model change does. Included here anyway (rather than omitted) so the plan
 * preview honestly surfaces "this will block your upgrade" up front; each such item's own
 * {@code description} says so explicitly.
 */
public final class MigrationPlanEmitter {

    private MigrationPlanEmitter() {
    }

    /**
     * @param newModel            the compiled model this generation run is producing (required)
     * @param previousModelOrNull the compiled model the app was PREVIOUSLY generated from -- read
     *                            by the caller from the previous FinalApp output's canonical JSON
     *                            (see this class's package javadoc / the plan's task 6.1 text for
     *                            exactly where that lives); {@code null} means "no previous model"
     *                            (fresh install), producing a trivial {@link MigrationPlan}.
     * @param databasePlan        the CURRENT generation run's already-computed database plan --
     *                            supplies {@code toFingerprint} (its {@code schemaFingerprint()},
     *                            used verbatim, never re-derived) and the engine/lifecycle/table-
     *                            creation flags needed to compute {@code fromFingerprint} against
     *                            the SAME algorithm for the previous model.
     */
    public static MigrationPlan compute(
            CompiledModel newModel,
            CompiledModel previousModelOrNull,
            GeneratedDatabasePlan databasePlan
    ) {
        Objects.requireNonNull(newModel, "newModel");
        Objects.requireNonNull(databasePlan, "databasePlan");

        String toFingerprint = databasePlan.schemaFingerprint();

        if (previousModelOrNull == null) {
            return new MigrationPlan(true, null, toFingerprint, List.of(), null);
        }

        String fromFingerprint = UserDatabaseDefinitionLoader.computeSchemaFingerprint(
                definitionForFingerprint(databasePlan), previousModelOrNull);

        List<PlanItem> items = new ArrayList<>();
        if (databasePlan.createBusinessTables()) {
            BusinessTableMetadata oldMeta = SchemaRealizationEmitter.computeBusinessTableMetadata(previousModelOrNull);
            BusinessTableMetadata newMeta = SchemaRealizationEmitter.computeBusinessTableMetadata(newModel);
            items.addAll(diffBusinessTables(oldMeta, newMeta));
        }
        items.sort(Comparator.comparing(MigrationPlanEmitter::sortKey));

        List<String> destructiveStableStrings = new ArrayList<>();
        for (PlanItem item : items) {
            if (item.destructive() && item.stableString() != null) {
                destructiveStableStrings.add(item.stableString());
            }
        }
        String ackToken = destructiveStableStrings.isEmpty()
                ? null
                : DestructiveAckToken.compute(toFingerprint, destructiveStableStrings);

        return new MigrationPlan(false, fromFingerprint, toFingerprint, items, ackToken);
    }

    /**
     * {@link UserDatabaseDefinitionLoader#computeSchemaFingerprint} only reads
     * engine/lifecycle-strategy/scope/table-creation flags off its {@link UserDatabaseDefinition}
     * parameter (see its own fingerprintInputs() javadoc) -- never host/port/credentials/paths --
     * so reconstructing one from {@link GeneratedDatabasePlan}'s already-resolved fields (rather
     * than re-reading the db-definition file a second time) is safe and produces an identical hash
     * input for those fields.
     */
    private static UserDatabaseDefinition definitionForFingerprint(GeneratedDatabasePlan databasePlan) {
        return new UserDatabaseDefinition(
                databasePlan.engine(), "", 0, "", "", "", "", "", "",
                databasePlan.createInternalTables(), databasePlan.createBusinessTables(),
                databasePlan.schemaLifecycle());
    }

    private static List<PlanItem> diffBusinessTables(BusinessTableMetadata oldMeta, BusinessTableMetadata newMeta) {
        List<PlanItem> items = new ArrayList<>();

        Set<String> oldTables = new LinkedHashSet<>(oldMeta.businessTables());
        Set<String> newTables = new LinkedHashSet<>(newMeta.businessTables());
        Set<String> missingTables = new LinkedHashSet<>(newTables);
        missingTables.removeAll(oldTables);
        Set<String> extraTables = new LinkedHashSet<>(oldTables);
        extraTables.removeAll(newTables);

        RenameResolution.Result tableResolution =
                RenameResolution.resolve(missingTables, extraTables, newMeta.businessTableRenames());

        // Every table needing a column-level diff: direct same-name overlaps, plus every explained
        // table rename (§2.4 ordering -- table renames are "applied" first, then column diffing
        // runs against the post-rename table identity, matching the executor's own step order).
        Map<String, String> columnDiffPairs = new LinkedHashMap<>();
        for (String table : oldTables) {
            if (newTables.contains(table)) {
                columnDiffPairs.put(table, table);
            }
        }
        for (Map.Entry<String, String> rename : tableResolution.explainedRenames().entrySet()) {
            items.add(PlanItem.renameTable(rename.getKey(), rename.getValue()));
            columnDiffPairs.put(rename.getKey(), rename.getValue());
        }
        for (String table : tableResolution.remainingMissing()) {
            items.add(PlanItem.addTable(table));
        }
        for (String table : tableResolution.remainingExtra()) {
            // Row count is unknowable without a live database -- see class javadoc's documented
            // fidelity limitation. -1 mirrors SchemaDeltaReport#bestEffortRowCount's own sentinel.
            items.add(PlanItem.dropTable(new SchemaDeltaItem.DropTable(table, -1L)));
        }

        for (Map.Entry<String, String> pair : columnDiffPairs.entrySet()) {
            items.addAll(diffColumns(pair.getKey(), pair.getValue(), oldMeta, newMeta));
            items.addAll(diffUniqueConstraints(pair.getKey(), pair.getValue(), oldMeta, newMeta));
        }

        return items;
    }

    private static List<PlanItem> diffColumns(
            String newTable, String oldTable, BusinessTableMetadata oldMeta, BusinessTableMetadata newMeta
    ) {
        List<PlanItem> items = new ArrayList<>();

        Set<String> newColumns = new LinkedHashSet<>(newMeta.businessTableColumns().getOrDefault(newTable, List.of()));
        Set<String> oldColumns = new LinkedHashSet<>(oldMeta.businessTableColumns().getOrDefault(oldTable, List.of()));
        Set<String> missingColumns = new LinkedHashSet<>(newColumns);
        missingColumns.removeAll(oldColumns);
        Set<String> extraColumns = new LinkedHashSet<>(oldColumns);
        extraColumns.removeAll(newColumns);

        Map<String, String> declaredRenames = newMeta.businessTableRenamedColumns().getOrDefault(newTable, Map.of());
        RenameResolution.Result resolution = RenameResolution.resolve(missingColumns, extraColumns, declaredRenames);

        Map<String, String> oldTypes = oldMeta.businessTableColumnTypes().getOrDefault(oldTable, Map.of());
        Map<String, String> newTypes = newMeta.businessTableColumnTypes().getOrDefault(newTable, Map.of());

        for (Map.Entry<String, String> rename : resolution.explainedRenames().entrySet()) {
            String newColumn = rename.getKey();
            String oldColumn = rename.getValue();
            items.add(PlanItem.renameColumn(newTable, newColumn, oldColumn));
            // Composability with a type change on the SAME (renamed) column, mirroring Phase 1's
            // classify() fix and Phase 3's composability: compare the OLD column's OLD type against
            // the NEW column's NEW type, not the (meaningless, cross-name) same-name lookup.
            addTypeChangeItemIfAny(items, newTable, newColumn, oldTypes.get(oldColumn), newTypes.get(newColumn));
        }

        Set<String> additiveEligible = new LinkedHashSet<>(
                newMeta.businessTableAdditiveColumns().getOrDefault(newTable, List.of()));
        Set<String> requiredColumns = new LinkedHashSet<>(
                newMeta.businessTableRequiredColumns().getOrDefault(newTable, List.of()));
        Map<String, String> defaultLiterals = newMeta.businessTableColumnDefaultLiterals().getOrDefault(newTable, Map.of());

        for (String column : resolution.remainingMissing()) {
            String toType = newTypes.get(column);
            if (!additiveEligible.contains(column)) {
                items.add(PlanItem.unknown(new SchemaDeltaItem.Unknown(
                        "Table '" + newTable + "' expects new column '" + column + "' (a required bond/reference "
                                + "field) which is not additive-eligible -- v1 has no backfill support for a required "
                                + "bond added to an existing populated table (LNCH-1 Phase 5); boot refuses until the "
                                + "bond is made optional. No acknowledgment token bypasses this -- it requires a model "
                                + "change.")));
                continue;
            }
            if (!requiredColumns.contains(column)) {
                items.add(PlanItem.addColumn(newTable, column, toType));
                continue;
            }
            String literalDefault = defaultLiterals.get(column);
            if (literalDefault != null) {
                items.add(PlanItem.addColumnBackfill(newTable, column, toType, literalDefault));
            } else {
                items.add(PlanItem.unknown(new SchemaDeltaItem.Unknown(
                        "Table '" + newTable + "' expects new required column '" + column + "' with no literal "
                                + "default declared -- only literal defaults backfill automatically in v1 (LNCH-1 "
                                + "Phase 5); boot refuses until a literal default is declared or the field is made "
                                + "optional. No acknowledgment token bypasses this -- it requires a model change.")));
            }
        }

        for (String column : resolution.remainingExtra()) {
            items.add(PlanItem.dropColumn(new SchemaDeltaItem.DropColumn(newTable, column, oldTypes.get(column))));
        }

        Set<String> sharedColumns = new LinkedHashSet<>(newColumns);
        sharedColumns.retainAll(oldColumns);
        for (String column : sharedColumns) {
            addTypeChangeItemIfAny(items, newTable, column, oldTypes.get(column), newTypes.get(column));
        }

        return items;
    }

    private static void addTypeChangeItemIfAny(
            List<PlanItem> items, String table, String column, String fromType, String toType
    ) {
        if (fromType == null || toType == null || fromType.equalsIgnoreCase(toType)) {
            return;
        }
        TypeChangeMatrix.Classification classification = TypeChangeMatrix.classify(fromType, toType);
        if (classification == TypeChangeMatrix.Classification.WIDENING) {
            items.add(PlanItem.widenType(table, column, fromType, toType));
        } else {
            items.add(PlanItem.narrowType(new SchemaDeltaItem.NarrowType(table, column, fromType, toType)));
        }
    }

    private static List<PlanItem> diffUniqueConstraints(
            String newTable, String oldTable, BusinessTableMetadata oldMeta, BusinessTableMetadata newMeta
    ) {
        List<PlanItem> items = new ArrayList<>();
        Set<String> oldNames = new LinkedHashSet<>();
        for (UniqueConstraintDecl decl : oldMeta.businessTableUniqueConstraints().getOrDefault(oldTable, List.of())) {
            oldNames.add(decl.name());
        }
        for (UniqueConstraintDecl decl : newMeta.businessTableUniqueConstraints().getOrDefault(newTable, List.of())) {
            if (!oldNames.contains(decl.name())) {
                items.add(PlanItem.addUniqueConstraint(newTable, decl.columns()));
            }
        }
        return items;
    }

    /** Deterministic ordering: table, then column, then kind, then description as a final
     * tiebreaker -- so {@link MigrationPlan#items()} (and therefore
     * {@link MigrationPlan#destructiveItemStableStrings()}) never depends on the underlying
     * {@code Map}/{@code Set} iteration order the two {@link BusinessTableMetadata} instances
     * happened to produce. */
    private static String sortKey(PlanItem item) {
        String table = item.table() == null ? "" : item.table();
        String column = item.column() == null ? "" : item.column();
        return table + ' ' + column + ' ' + item.kind().name() + ' ' + item.description();
    }
}
