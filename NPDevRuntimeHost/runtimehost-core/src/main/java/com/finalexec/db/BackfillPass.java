package com.finalexec.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.schemaevolution.RenameResolution;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

/**
 * T2.B.4 (pure mechanical extraction): LNCH-1 Phase 5's required-field backfill and required-bond
 * refusal passes, split out of {@link SchemaLifecycleExecutor} verbatim -- no behavior change. Both
 * entry points ({@code applyRequiredFieldBackfills}, {@code refuseIfRequiredBondColumnMissing}) were
 * {@code private} and not directly unit-tested (only reached via {@code beforeMigrate}/
 * {@code afterMigrate}, which stay on the executor), so they move here in full rather than leaving a
 * wrapper. Flat sibling in {@code com.finalexec.db}, not a subpackage -- see {@link TableRenamePass}'s
 * class javadoc for why.
 */
final class BackfillPass {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private BackfillPass() {
    }

    /**
     * LNCH-1 P5 (5.2). Called by {@code SchemaLifecycleExecutor#afterMigrate} at every point
     * classification (after Phases 1-3's rename/widening attempts) settles on {@code SAFE_ADDITIVE}
     * as the residual -- BEFORE that method returns {@code DestructiveRecreation.safeAdditiveOutcome()}
     * and therefore BEFORE {@code migrate}'s {@code flyway.migrate()} call ever runs the R__ repeatable
     * additive migration.
     *
     * <p><b>Why this must run ahead of Flyway, not after (see the class-level design note this
     * phase adds near {@code beforeMigrate}):</b> {@code appendAdditiveColumns} (generator-side)
     * unconditionally emits {@code ADD COLUMN IF NOT EXISTS} for every additive-eligible column,
     * including required ones with no viable backfill -- if this method let that migration run
     * first and only refused afterward, a refused required-field addition would still leave a
     * nullable column sitting in the live database ("never add it in the first place" is the
     * plan's explicit requirement). So this method:
     * <ol>
     *   <li><b>Pass 1 (read-only):</b> for every table, find required, additive-eligible columns
     *       missing from the live database. A column with a declared literal default is queued for
     *       backfill; one without (no default, or only an expression default -- v1 only backfills
     *       literals) is queued as a refusal. REG-61(b): a column that DOES have a literal default
     *       but is also UNIQUE-constrained, with more than one row that would receive that same
     *       value, is queued as its own named refusal instead -- a flat literal cannot satisfy
     *       uniqueness across more than one row, and letting it proceed would only trade this
     *       refusal for a confusing duplicate-key failure once {@code UniqueConstraintPass} re-adds
     *       the constraint later in the same boot. Nothing is written to the database in this pass.</li>
     *   <li>If ANY refusal was queued, throw before this method applies any backfill of its own --
     *       every pending backfill in this same boot is left un-backfilled, and the stored fingerprint
     *       is left stale so a fixed retry re-attempts cleanly. (Post-remediation-R2 this method runs
     *       from {@code afterMigrate}, i.e. AFTER {@code flyway.migrate()}, so on a real boot
     *       {@code appendAdditiveColumns}'s {@code ADD COLUMN IF NOT EXISTS} may already have added the
     *       column NULLABLE before this refusal -- harmless: it stays nullable and untightened until a
     *       fixed model backfills it. The direct-call unit tests bypass {@code flyway.migrate()}, so
     *       there the column is genuinely never added.)</li>
     *   <li><b>Pass 2 (apply):</b> only reached when every required column has a literal default.
     *       For each: {@code ADD COLUMN IF NOT EXISTS} (nullable) -&gt; {@code UPDATE ... SET c = ?
     *       WHERE c IS NULL} (the literal, bound as a JDBC parameter -- never string-interpolated
     *       into SQL text, see {@code decodeLiteralDefault}) -&gt; {@code ALTER COLUMN SET NOT
     *       NULL} (skipped if already NOT NULL, so crash-recovery re-runs converge instead of
     *       erroring). When Flyway's R__ migration runs afterward, its {@code ADD COLUMN IF NOT
     *       EXISTS} for this same column observes it already present -- a harmless no-op.</li>
     * </ol>
     *
     * <p>Idempotent by construction: live columns/nullability are read fresh from
     * {@link DatabaseMetaData} on every call, so a crash between two backfilled columns (or between
     * the ADD/UPDATE/SET-NOT-NULL steps of one column) converges cleanly on the next boot -- see
     * {@code SchemaLifecycleExecutorRequiredFieldBackfillCrashRecoveryTest}.
     */
    static void applyRequiredFieldBackfills(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest, String stored,
            SchemaLifecycleExecutor.SchemaChangeClassification classification) {
        record PendingBackfill(String table, String column, String sqlType, String literalDefaultJson) {
        }
        // SER-P4.6: which additive-eligible required columns need a literal-default backfill (pending) or
        // have no literal default and so refuse the boot (refusal) is derived from the canonical SchemaDiff
        // -- covering the missing case (ADD_REQUIRED_COLUMN) AND the crash-recovery half-applied case
        // (TIGHTEN_NOT_NULL: present-but-nullable; a converged present+NOT NULL column produces no diff
        // item and is correctly skipped). Each diff item's lower-cased name is resolved back to its
        // model-case table/column so the emitted DDL and refusal messages are byte-identical to the former
        // live-introspection loop. Proven equivalent at P4.6a.
        List<PendingBackfill> pending = new ArrayList<>();
        List<String> refusals = new ArrayList<>();
        // REG-61(b): a literal default writes the SAME value into every affected row, so it cannot
        // satisfy a UNIQUE constraint once more than one row needs it -- confirmed live on WmsOffice
        // (identity_roles.name, 5 rows; identity_users.username, 6 rows), where the backfill
        // "succeeded" only to have UniqueConstraintPass fail with a confusing duplicate-key error on
        // the SAME boot. Collected separately from `refusals` (which means "no default at all") and
        // given its own named diagnostic, matching the bond-column refusal precedent below.
        List<String> uniqueBackfillRefusals = new ArrayList<>();
        // Move 9 B1 (docs/ACCEPTED_BOUNDARIES.md B2): expression-default refusal candidates, checked
        // against a pending ControlPanel acknowledgment AFTER this scan (one token for the whole set,
        // matching the destructive-item acknowledgment convention) instead of refusing immediately.
        List<ExpressionBackfillPreview.Item> expressionCandidates = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (BackfillItem item : backfillItemsFromDiff(dataSource, manifest)) {
                String table = item.table();   // model-case
                String column = item.column(); // model-case
                if (item.refusal()) {
                    String expression = manifest.businessTableColumnDefaultExpressions()
                            .getOrDefault(table, Map.of()).get(column);
                    if (expression != null && !expression.isBlank()) {
                        expressionCandidates.add(ExpressionBackfillPreview.evaluate(connection, table, column, expression));
                        continue;
                    }
                    // Move 9 B1: a manifest generated BEFORE this feature only ever carries the boolean
                    // "has an expression default" flag (businessTableExpressionDefaultColumns), never
                    // the expression TEXT (businessTableColumnDefaultExpressions) -- with no text there
                    // is nothing to preview/evaluate/acknowledge, so this refuses with the SAME message
                    // as before this feature existed, unchanged.
                    boolean hasExpressionDefault = manifest.businessTableExpressionDefaultColumns()
                            .getOrDefault(table, List.of()).contains(column);
                    refusals.add(table + "." + column + (hasExpressionDefault
                            ? " (an expression default is declared, but only literal defaults are backfilled "
                                    + "automatically in v1 -- declare a literal default or make the field optional)"
                            : " (no default declared -- declare a literal default or make the field optional)"));
                    continue;
                }
                String literalDefaultJson = manifest.businessTableColumnDefaultLiterals()
                        .getOrDefault(table, Map.of()).get(column);
                String sqlType = manifest.businessTableColumnTypes().getOrDefault(table, Map.of()).get(column);
                if (isUniqueConstrained(manifest, table, column)) {
                    long affectedRows = countAffectedRows(connection, table, column);
                    if (affectedRows > 1) {
                        uniqueBackfillRefusals.add(table + "." + column + " (" + affectedRows + " existing row(s) "
                                + "would all receive the SAME literal default " + literalDefaultJson + ", which "
                                + "cannot satisfy the declared uniqueness -- a per-row-unique default is not "
                                + "expressible in v1)");
                        continue;
                    }
                }
                pending.add(new PendingBackfill(table, column, sqlType, literalDefaultJson));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed inspecting live database for unique-constrained required-field backfills", exception);
        }

        if (!uniqueBackfillRefusals.isEmpty()) {
            SchemaHistoryStore.writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification, null, null, "REFUSED");
            throw new IllegalStateException("Schema change adds new required, UNIQUE-constrained field(s) to "
                    + "table(s) with more than one existing row, but the declared literal default cannot express a "
                    + "per-row-unique value (LNCH-1 Phase 5 / REG-61): " + uniqueBackfillRefusals + ". Backfill these "
                    + "column(s) out-of-band before the next boot -- e.g. UPDATE <table> SET <column> = "
                    + "'<prefix>-' || CAST(id AS VARCHAR(36)) WHERE <column> IS NULL, then ALTER TABLE <table> "
                    + "ALTER COLUMN <column> SET NOT NULL -- or make the field optional. See "
                    + "docs/SCHEMA_EVOLUTION.md#new-required-fields.");
        }
        // Move 9 B1 (docs/ACCEPTED_BOUNDARIES.md B2): an expression default only ever backfills with an
        // explicit ControlPanel acknowledgment (the SAME PendingSchemaAcknowledgmentStore/ackToken
        // mechanism the destructive-item path uses, via SchemaAcknowledgmentController's existing
        // /acknowledge endpoint -- no new acknowledgment channel). Unacknowledged, this refuses the
        // boot exactly as it did before this feature existed.
        if (!expressionCandidates.isEmpty()) {
            String expectedToken = ExpressionBackfillPreview.expectedToken(manifest.schemaFingerprint(), expressionCandidates);
            boolean acknowledged = PendingSchemaAcknowledgmentStore
                    .findMatching(dataSource, manifest.schemaFingerprint(), expectedToken).isPresent();
            if (!acknowledged) {
                for (ExpressionBackfillPreview.Item candidate : expressionCandidates) {
                    // B2 (docs/ACCEPTED_BOUNDARIES.md, 2026-08-25 W2.3): the leading
                    // "B2:expression_backfill_requires_ack:" tag lets a boot log line or future
                    // orchestrator hook key on this specific refusal reason even though it may be
                    // thrown merged with unrelated LNCH-1 Phase 5 literal-default refusals below
                    // (`refusals` is one shared list for both reasons, by design -- an operator
                    // sees every blocking reason in one boot attempt, not one at a time).
                    refusals.add("B2:expression_backfill_requires_ack:" + candidate.table() + "." + candidate.column()
                            + " (an expression default is declared, but only literal defaults are backfilled "
                            + "automatically in v1 unless explicitly acknowledged -- preview it via GET "
                            + "/api/admin/schema-migration/expression-backfill-preview, then acknowledge via POST "
                            + "/api/admin/schema-migration/acknowledge with ackToken=" + expectedToken + ", "
                            + "declare a literal default instead, or make the field optional)");
                }
            } else {
                List<String> failing = expressionCandidates.stream()
                        .filter(ExpressionBackfillPreview.Item::hasFailures)
                        .map(candidate -> candidate.table() + "." + candidate.column()
                                + " (failed for row id(s): " + candidate.failedRowIds() + ")")
                        .toList();
                if (!failing.isEmpty()) {
                    SchemaHistoryStore.writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification, null, null, "REFUSED");
                    throw new IllegalStateException("Schema change adds new required field(s) with an ACKNOWLEDGED "
                            + "expression-default backfill, but the expression produces no value for at least one "
                            + "existing row (Move 9 B1): " + failing + ". Fix the expression or the offending "
                            + "row(s), then re-preview and re-acknowledge -- see docs/SCHEMA_EVOLUTION.md#new-required-fields.");
                }
                List<String> expressionBackfilled = new ArrayList<>();
                try {
                    SchemaHistoryStore.recordStepPass(dataSource, manifest, "EXPRESSION_BACKFILL",
                            expressionCandidates.stream()
                                    .map(candidate -> "BACKFILL " + candidate.table() + "." + candidate.column()
                                            + " EXPRESSION " + candidate.expression())
                                    .toList(),
                            () -> {
                                try (Connection connection = dataSource.getConnection()) {
                                    for (ExpressionBackfillPreview.Item candidate : expressionCandidates) {
                                        applyExpressionBackfill(connection, manifest, candidate);
                                        expressionBackfilled.add(candidate.table() + "." + candidate.column());
                                    }
                                }
                            });
                } catch (SQLException exception) {
                    throw new IllegalStateException("Failed applying acknowledged expression-default backfill(s) ("
                            + expressionBackfilled.size() + "/" + expressionCandidates.size()
                            + " applied before failure: " + expressionBackfilled + ")", exception);
                }
                System.out.println("NPDev schema lifecycle: added and backfilled new required column(s) using their "
                        + "ACKNOWLEDGED expression default (Move 9 B1): " + expressionBackfilled);
            }
        }
        if (!refusals.isEmpty()) {
            SchemaHistoryStore.writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification, null, null, "REFUSED");
            throw new IllegalStateException("Schema change adds new required field(s) to table(s) with existing "
                    + "data, but no literal default is available to backfill automatically (LNCH-1 Phase 5): "
                    + refusals + ". Declare a literal 'default' on the field, or make it optional -- see "
                    + "docs/SCHEMA_EVOLUTION.md#new-required-fields.");
        }
        if (pending.isEmpty()) {
            return;
        }
        List<String> backfilled = new ArrayList<>();
        // R4 (F5): one write-before-execute audit row for the whole required-field backfill pass.
        List<String> itemDetails = new ArrayList<>();
        for (PendingBackfill item : pending) {
            itemDetails.add("BACKFILL " + item.table() + "." + item.column() + " DEFAULT " + item.literalDefaultJson());
        }
        try {
            SchemaHistoryStore.recordStepPass(dataSource, manifest, "REQUIRED_BACKFILL", itemDetails, () -> {
                try (Connection connection = dataSource.getConnection()) {
                    for (PendingBackfill item : pending) {
                        addBackfillAndTightenColumn(connection, item.table(), item.column(),
                                item.sqlType(), item.literalDefaultJson());
                        backfilled.add(item.table() + "." + item.column());
                    }
                }
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed applying required-field backfill(s) (" + backfilled.size() + "/"
                    + pending.size() + " applied before failure: " + backfilled + ")", exception);
        }
        System.out.println("NPDev schema lifecycle: added and backfilled new required column(s) to their declared "
                + "literal default, then enforced NOT NULL (LNCH-1 Phase 5): " + backfilled);
    }

    /** One required-field backfill decision derived from the canonical diff (SER-P4.6), in model-case:
     * an additive-eligible required column that needs a literal-default backfill ({@code refusal=false},
     * from a NEEDS_BACKFILL item) or has no literal default and so refuses the boot ({@code refusal=true},
     * from a NEEDS_HOOK item). Covers the MISSING case (ADD_REQUIRED_COLUMN) and the crash-recovery
     * half-applied case (TIGHTEN_NOT_NULL: present-but-nullable); platform repair (TIGHTEN_PLATFORM) and
     * required bonds (non-additive) are OTHER passes and excluded.
     *
     * <p>Package-private (Move 9 B1, docs/ACCEPTED_BOUNDARIES.md B2): {@link ExpressionBackfillPreview},
     * a flat sibling in this same package, reuses this exact derivation for its dry-run preview --
     * one source of truth for "which columns are pending a required-field backfill." */
    record BackfillItem(String table, String column, boolean refusal) {
    }

    static List<BackfillItem> backfillItemsFromDiff(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        com.finalexec.db.schemastate.CurrentSchema current =
                new com.finalexec.db.schemastate.CurrentSchemaReader().read(dataSource);
        com.finalexec.db.schemastate.SchemaDiff diff = new com.finalexec.db.schemastate.SchemaDiffEngine()
                .diff(DesiredSchemaFactory.fromManifest(manifest),
                        ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest));
        List<BackfillItem> items = new ArrayList<>();
        for (com.finalexec.db.schemastate.SchemaDiffItem di : diff.items()) {
            String key = di.itemKey();
            if (!key.startsWith("ADD_REQUIRED_COLUMN:") && !key.startsWith("TIGHTEN_NOT_NULL:")) {
                continue;
            }
            // The diff canonicalises names to lower-case; resolve back to the manifest's model-case so the
            // emitted DDL and refusal messages stay byte-identical to the former loop.
            String modelTable = resolveModelTable(manifest, di.table());
            if (modelTable == null) {
                continue;
            }
            String modelColumn = resolveModelColumn(manifest, modelTable, di.column());
            if (modelColumn == null) {
                continue;
            }
            // This pass only converts additive-eligible required columns; required bonds (non-additive)
            // and platform columns are refused / repaired by separate passes.
            if (!containsIgnoreCase(manifest.businessTableRequiredColumns().getOrDefault(modelTable, List.of()), di.column())
                    || !containsIgnoreCase(manifest.businessTableAdditiveColumns().getOrDefault(modelTable, List.of()), di.column())) {
                continue;
            }
            if (di.safetyClass() == com.finalexec.db.schemastate.SafetyClass.NEEDS_BACKFILL) {
                items.add(new BackfillItem(modelTable, modelColumn, false));
            } else if (di.safetyClass() == com.finalexec.db.schemastate.SafetyClass.NEEDS_HOOK) {
                items.add(new BackfillItem(modelTable, modelColumn, true));
            }
        }
        return items;
    }

    /** The manifest table whose lower-cased name equals {@code lowerTable} (the diff's canonical form). */
    private static String resolveModelTable(SchemaLifecycleExecutor.SchemaManifest manifest, String lowerTable) {
        for (String table : manifest.businessTableColumns().keySet()) {
            if (table.toLowerCase(Locale.ROOT).equals(lowerTable)) {
                return table;
            }
        }
        return null;
    }

    /** The model-case column of {@code modelTable} whose lower-cased name equals {@code lowerColumn}. */
    private static String resolveModelColumn(SchemaLifecycleExecutor.SchemaManifest manifest, String modelTable, String lowerColumn) {
        for (String column : manifest.businessTableColumns().getOrDefault(modelTable, List.of())) {
            if (column.toLowerCase(Locale.ROOT).equals(lowerColumn)) {
                return column;
            }
        }
        return null;
    }

    /** REG-61(b): is {@code column} part of ANY unique constraint the model declares on {@code table}
     * (single-field or compound)? A flat literal backfill cannot satisfy uniqueness once more than
     * one row needs it, regardless of whether the constraint is single- or multi-column. */
    private static boolean isUniqueConstrained(SchemaLifecycleExecutor.SchemaManifest manifest, String table, String column) {
        for (SchemaLifecycleExecutor.UniqueConstraintDecl decl : manifest.businessTableUniqueConstraints().getOrDefault(table, List.of())) {
            for (String declColumn : decl.columns()) {
                if (declColumn.equalsIgnoreCase(column)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** REG-61(b): how many rows would receive the SAME literal default if this column were backfilled
     * now. If the column already exists live (crash-recovery / TIGHTEN_NOT_NULL case), only the
     * currently-NULL rows are affected; if it does not exist yet (ADD_REQUIRED_COLUMN case), every
     * row in the table would be, since none of them have a value for it.
     *
     * <p>Deliberately counts globally, not per-tenant, even though the unique constraints this
     * project generates are typically tenant-scoped (a collision only actually needs two rows in
     * the SAME tenant). A global count is conservative, never unsafe: it can only refuse a case a
     * per-tenant count would have allowed, never the reverse. Scoring per-tenant would need to know
     * whether THIS constraint is tenant-scoped and then take a per-tenant max, which is unnecessary
     * complexity beyond what the register's filed scope asks for. */
    private static long countAffectedRows(Connection connection, String table, String column) throws SQLException {
        String quotedTable = SchemaLifecycleExecutor.quotedIdentifier(table);
        boolean columnExistsLive = SchemaLifecycleExecutor.readActualColumns(connection.getMetaData(), table).stream()
                .anyMatch(column::equalsIgnoreCase);
        String sql = columnExistsLive
                ? "SELECT COUNT(*) FROM " + quotedTable + " WHERE "
                        + SchemaLifecycleExecutor.quotedIdentifier(column) + " IS NULL"
                : "SELECT COUNT(*) FROM " + quotedTable;
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    /** Case-insensitive membership: {@code lowerTarget} is already lower-cased (the diff canonicalises
     * column names); the model-case manifest list entries are lower-cased for the comparison. */
    private static boolean containsIgnoreCase(List<String> values, String lowerTarget) {
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).equals(lowerTarget)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code ADD COLUMN IF NOT EXISTS} (nullable) -&gt; bound-parameter {@code UPDATE ... WHERE c
     * IS NULL} -&gt; {@code SET NOT NULL} (skipped if already so). Every step idempotent-by-check
     * for crash recovery -- see {@link #applyRequiredFieldBackfills}'s class-level note.
     *
     * <p>No engine dialect branch is needed here (unlike rename/widen): {@code ADD COLUMN IF NOT
     * EXISTS} and {@code ALTER COLUMN ... SET NOT NULL} are both identical syntax on H2 and
     * Postgres, confirmed against a real H2 instance.
     */
    private static void addBackfillAndTightenColumn(Connection connection, String table, String column,
            String sqlType, String literalDefaultJson) throws SQLException {
        // `safeTable`/`safeColumn` stay RAW: guardedAddColumn puts them into an
        // information_schema string LITERAL, where a quoted name would never match. The
        // quoted pair is for the statement TEXT (STOR-6).
        String safeTable = SchemaLifecycleExecutor.safeIdentifier(table);
        String safeColumn = SchemaLifecycleExecutor.safeIdentifier(column);
        String quotedTable = SchemaLifecycleExecutor.quotedIdentifier(table);
        String quotedColumn = SchemaLifecycleExecutor.quotedIdentifier(column);
        String safeType = TypeWideningPass.safeSqlType(sqlType);
        try (PreparedStatement add = connection.prepareStatement(
                com.npdev.kernel.storage.sql.SqlDialects.active().guardedAddColumn(
                        safeTable, safeColumn,
                        "ALTER TABLE " + quotedTable + " ADD COLUMN " + quotedColumn + " " + safeType))) {
            add.executeUpdate();
        }
        Object literalValue = decodeLiteralDefault(literalDefaultJson);
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + quotedTable + " SET " + quotedColumn + " = ? WHERE "
                        + quotedColumn + " IS NULL")) {
            update.setObject(1, literalValue);
            update.executeUpdate();
        }
        if (!SchemaLifecycleExecutor.isColumnNotNull(connection, table, column)) {
            try (PreparedStatement notNull = connection.prepareStatement(
                    "ALTER TABLE " + quotedTable + " ALTER COLUMN " + quotedColumn + " SET NOT NULL")) {
                notNull.executeUpdate();
            }
        }
    }

    /**
     * Move 9 B1 (docs/ACCEPTED_BOUNDARIES.md B2): the ACKNOWLEDGED-expression-default twin of
     * {@link #addBackfillAndTightenColumn} -- {@code ADD COLUMN IF NOT EXISTS} (nullable), then a
     * PER-ROW {@code UPDATE ... WHERE id = ?} (a per-row COMPUTED value, unlike the literal path's
     * one bound parameter for every row), then {@code SET NOT NULL}. Re-evaluates the expression
     * FRESH against the live database right before writing -- never trusts the acknowledgment-matched
     * preview's own snapshot, which may be stale by the time this actually runs (a row could have
     * changed between preview and apply within the same boot).
     */
    private static void applyExpressionBackfill(
            Connection connection, SchemaLifecycleExecutor.SchemaManifest manifest, ExpressionBackfillPreview.Item item
    ) throws SQLException {
        String table = item.table();
        String column = item.column();
        // `safeTable`/`safeColumn` stay RAW: guardedAddColumn puts them into an
        // information_schema string LITERAL, where a quoted name would never match. The
        // quoted pair is for the statement TEXT (STOR-6).
        String safeTable = SchemaLifecycleExecutor.safeIdentifier(table);
        String safeColumn = SchemaLifecycleExecutor.safeIdentifier(column);
        String quotedTable = SchemaLifecycleExecutor.quotedIdentifier(table);
        String quotedColumn = SchemaLifecycleExecutor.quotedIdentifier(column);
        String quotedIdColumn = SchemaLifecycleExecutor.quotedIdentifier("id");
        String sqlType = manifest.businessTableColumnTypes().getOrDefault(table, Map.of()).get(column);
        String safeType = TypeWideningPass.safeSqlType(sqlType);
        try (PreparedStatement add = connection.prepareStatement(
                com.npdev.kernel.storage.sql.SqlDialects.active().guardedAddColumn(
                        safeTable, safeColumn,
                        "ALTER TABLE " + quotedTable + " ADD COLUMN " + quotedColumn + " " + safeType))) {
            add.executeUpdate();
        }
        List<ExpressionBackfillPreview.RowValue> rows =
                ExpressionBackfillPreview.evaluateRows(connection, table, column, item.expression());
        for (ExpressionBackfillPreview.RowValue row : rows) {
            if (row.value() == null) {
                throw new IllegalStateException("Expression default re-evaluation for " + table + "." + column
                        + " row id " + row.displayId() + " produced no value at apply time (it did not during the "
                        + "acknowledged preview) -- refusing to backfill with a partial result. Re-preview and "
                        + "re-acknowledge before retrying.");
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + quotedTable + " SET " + quotedColumn + " = ? WHERE "
                            + quotedIdColumn + " = ?")) {
                update.setObject(1, row.value());
                update.setObject(2, row.rawId());
                update.executeUpdate();
            }
        }
        if (!SchemaLifecycleExecutor.isColumnNotNull(connection, table, column)) {
            try (PreparedStatement notNull = connection.prepareStatement(
                    "ALTER TABLE " + quotedTable + " ALTER COLUMN " + quotedColumn + " SET NOT NULL")) {
                notNull.executeUpdate();
            }
        }
    }

    /**
     * Decodes a manifest-carried literal default (JSON-encoded by the generator, see
     * {@code SchemaRealizationEmitter#columnDefaultLiterals}) back to a typed Java value
     * (String/Integer/Double/Boolean/null) for use as a JDBC bound parameter -- deliberately never
     * string-interpolated into SQL text (guardrail 11's identifier-safety discipline extended to
     * VALUE safety, per the plan).
     */
    private static Object decodeLiteralDefault(String literalDefaultJson) {
        try {
            return OBJECT_MAPPER.readValue(literalDefaultJson, Object.class);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed decoding literal default from schema realization manifest: " + literalDefaultJson, exception);
        }
    }

    /**
     * LNCH-1 P5 (5.3). Called by {@code beforeMigrate} unconditionally, once Phases 1-3's rename/
     * widening attempts have run their course but BEFORE {@code SchemaDeltaReport} (Phase 4's
     * destructive-report machinery) is ever invoked. Independently re-derives, per table, the
     * residual missing-column set (live columns vs. manifest-expected, minus anything explained by
     * a declared rename) -- the SAME computation {@code SchemaDeltaReport} makes, deliberately not
     * trusting {@code classify}'s aggregate return value (which short-circuits to
     * {@code DESTRUCTIVE} the moment ANY table looks bad, without evaluating the rest) so this check
     * is correct regardless of what else is happening on other tables in the same boot.
     *
     * <p>A required column that is missing AND not additive-eligible is -- after the LNCH-1 P5
     * (5.3) change to {@code isAdditiveEligible} -- necessarily a REQUIRED bond/FK field (the only
     * remaining reason a required column can fail additive-eligibility; a plain required field is
     * always additive-eligible and is {@link #applyRequiredFieldBackfills}'s concern instead). A
     * bond has no literal-default backfill possible in v1 (its "default" would need to reference an
     * existing row's actual key), so this always refuses -- intercepting the case with a dedicated,
     * itemized message BEFORE {@code SchemaDeltaReport} would otherwise have to fall back to its
     * generic {@code Unknown} item kind for it (moving it out of that bucket, per the plan).
     */
    static void refuseIfRequiredBondColumnMissing(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest, String stored,
            SchemaLifecycleExecutor.SchemaChangeClassification classification) {
        List<String> violations = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (Map.Entry<String, List<String>> entry : manifest.businessTableColumns().entrySet()) {
                String table = entry.getKey();
                List<String> requiredColumns = manifest.businessTableRequiredColumns().getOrDefault(table, List.of());
                if (requiredColumns.isEmpty()) {
                    continue;
                }
                Set<String> actualColumns = SchemaLifecycleExecutor.readActualColumns(metadata, table);
                if (actualColumns.isEmpty()) {
                    continue; // brand-new table -- nothing missing, nothing to refuse
                }
                Set<String> expected = new LinkedHashSet<>(entry.getValue());
                Set<String> extraInDb = new LinkedHashSet<>(actualColumns);
                extraInDb.removeAll(expected);
                Set<String> missingInDb = new LinkedHashSet<>(expected);
                missingInDb.removeAll(actualColumns);
                if (missingInDb.isEmpty()) {
                    continue;
                }
                // REG-6: "a required column missing AND not additive-eligible is a required bond" is
                // no longer re-derived inline here — it is ColumnFacts.bond(), computed once per column.
                Map<String, SchemaLifecycleExecutor.ColumnFacts> facts = SchemaLifecycleExecutor.columnFactsFor(manifest, table);
                Map<String, String> renames = manifest.businessTableRenamedColumns().getOrDefault(table, Map.of());
                RenameResolution.Result resolution = RenameResolution.resolve(missingInDb, extraInDb, renames);
                for (String column : resolution.remainingMissing()) {
                    SchemaLifecycleExecutor.ColumnFacts columnFacts = facts.get(column);
                    if (columnFacts != null && columnFacts.bond()) {
                        violations.add(table + "." + column);
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed inspecting live database for required bond field additions", exception);
        }
        if (violations.isEmpty()) {
            return;
        }
        SchemaHistoryStore.writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification, null, null, "REFUSED");
        throw new IllegalStateException("Schema change adds new required bond/reference field(s) to table(s) with "
                + "existing data: " + violations + ". A required bond has no automatic literal-default backfill in "
                + "v1 (its value would need to reference an existing row's actual key) -- make the field optional, "
                + "or use the itemized destructive-acknowledgment path (LNCH-1 Phase 4) to recreate the table -- see "
                + "docs/SCHEMA_EVOLUTION.md#new-required-fields.");
    }
}
