package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledIndex;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelDataSource;
import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.dsl.v1.compiled.SqlTypeSupport;
import com.npdev.generator.bonds.BondModelSupport;
import com.npdev.generator.bonds.BondModelSupport.Bond;
import com.npdev.generator.bonds.BondModelSupport.Cardinality;
import com.npdev.kernel.dbschema.InternalColumnDefinition;
import com.npdev.kernel.dbschema.InternalColumnType;
import com.npdev.kernel.dbschema.InternalIndexDefinition;
import com.npdev.kernel.dbschema.InternalTableDefinition;
import com.npdev.kernel.dbschema.NpdevInternalTables;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SchemaRealizationEmitter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public void emit(CompiledModel model, Path outRoot, GeneratedDatabasePlan plan, Path modelSourcePath) throws Exception {
        emit(model, outRoot, plan, modelSourcePath, List.of());
    }

    /**
     * LNCH-1 P6 (task 6.3): {@code migrationPlanItemStableStrings} is the destructive-item stable
     * strings from a {@code com.npdev.generator.schemaevolution.MigrationPlan} computed THIS
     * generation pass (via {@code GeneratorMain}'s optional {@code --previous-compiled-model} /
     * {@code --migration-plan-out} flags), or an empty list when no plan was computed this pass
     * (the ordinary case -- existing callers using the 4-arg {@link #emit} overload above see zero
     * behavior change: an empty list serializes identically to "absent" as far as
     * {@code SchemaLifecycleExecutor}'s manifest reader is concerned). Threaded into the manifest so
     * the runtime executor can print BOTH "what the plan expected" and "what it found live at boot"
     * when they disagree (see {@code SchemaLifecycleExecutor}'s agreement-check enrichment).
     */
    public void emit(
            CompiledModel model,
            Path outRoot,
            GeneratedDatabasePlan plan,
            Path modelSourcePath,
            List<String> migrationPlanItemStableStrings
    ) throws Exception {
        emit(model, outRoot, plan, modelSourcePath, migrationPlanItemStableStrings, null);
    }

    /**
     * LNCH-1 P6 (task 6.2b): {@code destructiveAcknowledgmentToken} is {@code GeneratorMain}'s new,
     * optional {@code --destructiveAcknowledgment} CLI flag value, written verbatim into the
     * manifest's {@code destructiveAcknowledgment} key -- the value {@code SchemaLifecycleExecutor}'s
     * Phase 4 destructive-path token check reads at boot (that field existed on {@code SchemaManifest}
     * since Phase 4, and {@code SchemaLifecycleExecutor#loadManifest} already parses this exact key,
     * but nothing generator-side ever WROTE a real value into it until this flag -- confirmed by
     * grepping this class for {@code destructiveAcknowledgment} before this change: no match). {@code null}
     * or blank is written as {@code ""}, matching the manifest shape every prior phase already
     * produced (the 5-arg overload above delegates here with {@code null} -- zero behavior change
     * for every existing caller).
     */
    public void emit(
            CompiledModel model,
            Path outRoot,
            GeneratedDatabasePlan plan,
            Path modelSourcePath,
            List<String> migrationPlanItemStableStrings,
            String destructiveAcknowledgmentToken
    ) throws Exception {
        if (outRoot == null || plan == null) {
            return;
        }
        Path resourcesRoot = outRoot.resolve("src").resolve("main").resolve("resources");
        Files.createDirectories(resourcesRoot);
        emitSchemaArtifacts(model, resourcesRoot, plan);
        emitManifest(model, resourcesRoot, plan, modelSourcePath,
                migrationPlanItemStableStrings == null ? List.of() : migrationPlanItemStableStrings,
                destructiveAcknowledgmentToken == null ? "" : destructiveAcknowledgmentToken.trim());
        emitApplicationProperties(resourcesRoot, plan);
    }

    private static void emitSchemaArtifacts(CompiledModel model, Path resourcesRoot, GeneratedDatabasePlan plan) throws Exception {
        Path schemaDir = resourcesRoot.resolve("db").resolve("schema-realization");
        Files.createDirectories(schemaDir);
        if (!plan.jdbc()) {
            Files.writeString(
                    schemaDir.resolve("in-memory-logical-stores.json"),
                    OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(storeMetadata(model, plan)) + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );
            return;
        }

        StringBuilder sql = new StringBuilder();
        sql.append("-- NPDev schema realization\n");
        sql.append("-- Engine: ").append(plan.engine().externalName()).append("\n");
        sql.append("-- Schema fingerprint: ").append(plan.schemaFingerprint()).append("\n\n");
        if (plan.createInternalTables()) {
            for (InternalTableDefinition table : NpdevInternalTables.all()) {
                appendTable(sql, table, plan.engine());
            }
        }
        if (plan.createBusinessTables()) {
            Map<String, CompiledConcept> conceptsByName = BondModelSupport.conceptsByName(model);
            for (CompiledConcept concept : model.getConcepts()) {
                validateNoReservedColumnCollision(concept);
            }
            // LNCH-6: the fields a compiled panel/query filters, sorts, or joins children by get a
            // tenant-composite secondary index, so the LNCH-5 SQL push-down uses index scans instead
            // of sequential scans on large tables.
            Map<String, Set<String>> implicitIndexFields = collectImplicitIndexFields(model);
            for (CompiledConcept concept : model.getConcepts()) {
                appendBusinessTable(sql, concept, plan.engine(), conceptsByName,
                        implicitIndexFields.getOrDefault(concept.getName().toLowerCase(Locale.ROOT), Set.of()));
            }
            appendBonds(sql, model, plan.engine(), conceptsByName);
        }
        Files.writeString(schemaDir.resolve("V1__npdev_schema_realization.sql"), sql.toString(), StandardCharsets.UTF_8);

        if (plan.createBusinessTables() || plan.createInternalTables()) {
            StringBuilder additive = new StringBuilder();
            additive.append("-- NPDev safe-additive schema columns (Flyway repeatable migration)\n");
            additive.append("-- Adds new non-bond columns to already-existing tables (internal + business) without\n");
            additive.append("-- destructive recreation, and self-heals a business table (or bond junction table)\n");
            additive.append("-- that doesn't exist yet on this database -- CREATE TABLE IF NOT EXISTS is a no-op\n");
            additive.append("-- the instant it does (REG-40 tactical hotfix). Scope boundary: internal tables stay\n");
            additive.append("-- V1-only (platform-fixed, not model-driven); column/table removal and type changes\n");
            additive.append("-- remain structural changes handled by the schema-fingerprint destructive-recreate path.\n\n");

            // Ordering rule (REG-40 tactical hotfix): all CREATE TABLE blocks -> all ADD COLUMN
            // blocks -> all constraint blocks. A brand-new table must exist before its additive
            // columns run against it, and a bond FK must come after both endpoint tables exist --
            // which this ordering guarantees regardless of which tables/columns are actually new.

            // 1. CREATE TABLE IF NOT EXISTS blocks (business tables, then their bond junction tables).
            if (plan.createBusinessTables()) {
                Map<String, CompiledConcept> conceptsByName = BondModelSupport.conceptsByName(model);
                for (CompiledConcept concept : model.getConcepts()) {
                    appendBusinessTableShape(additive, concept, plan.engine(), conceptsByName);
                }
                appendJunctionTableShapes(additive, model, plan.engine());
            }

            // 2. ADD COLUMN blocks.
            // Internal tables previously had NO column-evolution path at all -- appendTable() only ever
            // emits CREATE TABLE IF NOT EXISTS, which is a no-op the instant the table already exists.
            // A new column added to an internal table definition (e.g. NpdevTenantTable) would silently
            // never reach an already-booted app's database. Mirrors the business-table additive path,
            // including its "downgrade a required-but-undefaulted column to nullable" safety net.
            if (plan.createInternalTables()) {
                for (InternalTableDefinition table : NpdevInternalTables.all()) {
                    appendInternalTableAdditiveColumns(additive, table, plan.engine());
                }
            }
            if (plan.createBusinessTables()) {
                Map<String, CompiledConcept> conceptsByName = BondModelSupport.conceptsByName(model);
                for (CompiledConcept concept : model.getConcepts()) {
                    appendAdditiveColumns(additive, concept, plan.engine(), conceptsByName);
                }
            }

            // 3. Constraint/index blocks (unique/secondary/explicit indexes, then bond FKs).
            if (plan.createBusinessTables()) {
                Map<String, Set<String>> implicitIndexFields = collectImplicitIndexFields(model);
                for (CompiledConcept concept : model.getConcepts()) {
                    appendBusinessTableConstraints(additive, concept, plan.engine(),
                            implicitIndexFields.getOrDefault(concept.getName().toLowerCase(Locale.ROOT), Set.of()));
                }
                appendBondConstraints(additive, model, plan.engine());
            }

            Files.writeString(schemaDir.resolve("R__npdev_schema_additive_columns.sql"), additive.toString(), StandardCharsets.UTF_8);
        }
    }

    private static void appendInternalTableAdditiveColumns(StringBuilder sql, InternalTableDefinition table, DatabaseEngine engine) {
        for (InternalColumnDefinition column : table.columns()) {
            sql.append("ALTER TABLE ").append(table.name()).append(" ADD COLUMN IF NOT EXISTS ")
                    .append(column.name()).append(" ").append(renderInternalType(column.type(), engine));
            if (!column.defaultExpression().isBlank()) {
                sql.append(" DEFAULT ").append(column.defaultExpression());
            } else if (column.required()) {
                sql.append("; -- NOTE: '").append(column.name()).append("' on ").append(table.name())
                        .append(" is required but added nullable here (no default declared); ")
                        .append("existing rows will have NULL until backfilled\n");
                continue;
            }
            sql.append(";\n");
        }
        sql.append("\n");
    }

    /**
     * Adds new columns to an already-existing business table (LNCH-1 P5 (5.3): now including
     * nullable bond/FK columns, see {@link #isAdditiveEligible}). Always nullable at the SQL level,
     * even if the field is required by the model -- so this migration itself never breaks on
     * existing rows. On the SAME boot, AFTER this repeatable migration has added the column
     * (nullable), {@code SchemaLifecycleExecutor}'s {@code afterMigrate} step (LNCH-1 Phase 5;
     * enforcement consolidated to that single call site by remediation R2) backfills a required
     * column with its declared literal default and tightens it to NOT NULL (its own
     * {@code ADD COLUMN IF NOT EXISTS} is then a no-op, since this migration already added the
     * column); a required field with no literal default refuses the boot at that same
     * post-migration enforcement step. This holds on every upgrade boot regardless of what else the
     * upgrade contains, including one that also carries an acknowledged destructive item. Narrowing
     * type changes and column/table removal remain structural changes that go through the
     * schema-fingerprint destructive-recreate path instead.
     */
    private static void appendAdditiveColumns(StringBuilder sql, CompiledConcept concept, DatabaseEngine engine,
            Map<String, CompiledConcept> conceptsByName) {
        String table = SqlIdentifierSupport.tableName(concept);
        // DEFAULT 'default' backfills pre-existing rows when tenant_id is added to a table in place
        // (the safe-additive path). Without it, an in-place upgrade would leave legacy rows NULL and
        // every tenant-scoped read (WHERE tenant_id = ?) would silently make all existing data
        // unreachable. The DB-level DEFAULT also guards the kernel-injected-synthetic-column class of
        // bug, mirroring how 'version BIGINT NOT NULL DEFAULT 0' is handled.
        sql.append("ALTER TABLE ").append(table).append(" ADD COLUMN IF NOT EXISTS tenant_id ")
                .append(renderType("VARCHAR(120)", engine)).append(" DEFAULT 'default';\n");
        // LNCH-16: same in-place-upgrade reasoning as tenant_id above -- DEFAULT 0 backfills
        // pre-existing rows so JdbcBusinessConceptStore's row_version-aware save path (gated on
        // TableColumns#has("row_version")) works the moment this column lands, no separate
        // migration step needed.
        sql.append("ALTER TABLE ").append(table).append(" ADD COLUMN IF NOT EXISTS row_version ")
                .append(renderType("BIGINT", engine)).append(" DEFAULT 0;\n");
        // LNCH-1 T2 (finding T-B2): 'version' is added here for exactly the same reason, and with
        // exactly the same type and default, as row_version above. Before this it was the one column
        // fullColumnNames DECLARED but additiveColumnNames never marked additive, so no migration
        // could ever add it to an existing table: a table missing it produced an UNKNOWN delta item,
        // and since closeout C1 an UNKNOWN REFUSES the boot unless an itemized token authorizing a
        // whole-schema wipe is supplied. A missing platform column now self-heals instead.
        sql.append("ALTER TABLE ").append(table).append(" ADD COLUMN IF NOT EXISTS version ")
                .append(renderType("BIGINT", engine)).append(" DEFAULT 0;\n");
        // DELIBERATE ASYMMETRY (LNCH-1 T2, do not "fix" by adding NOT NULL here): the three platform
        // columns above are emitted with a DEFAULT but WITHOUT NOT NULL, while appendBusinessTable's
        // fresh CREATE TABLE emits them NOT NULL DEFAULT. Adding NOT NULL to an ADD COLUMN against a
        // table that already has rows is engine-dependent and fragile (the constraint is evaluated
        // before the default is applied on some engines). The two shapes CONVERGE instead:
        // SchemaLifecycleExecutor#tightenPlatformColumns (LNCH-1 T1, finding T-B1) runs on the
        // following boot, backfills any NULLs to these same defaults, and restores NOT NULL.
        for (CompiledField field : concept.getFields()) {
            if (!isAdditiveEligible(concept, field, conceptsByName)) {
                continue;
            }
            String column = SqlIdentifierSupport.columnName(field);
            Optional<Bond> bond = BondModelSupport.resolveBond(concept, field, conceptsByName);
            // A bond column takes the bound anchor's SQL type (matches appendBusinessTable's
            // fresh-CREATE handling), not the reference default (UUID).
            String sqlType = bond.isPresent() ? bond.get().effectiveSqlType() : SqlTypeSupport.sqlType(field);
            sql.append("ALTER TABLE ").append(table).append(" ADD COLUMN IF NOT EXISTS ")
                    .append(column).append(" ").append(renderType(sqlType, engine)).append(";\n");
            if (field.isRequired()) {
                CompiledSchema schema = field.getSchema();
                Object literalDefault = schema == null ? null : schema.getDefaultValue();
                if (literalDefault != null) {
                    sql.append("-- NOTE: '").append(column).append("' is required by the model and declares a ")
                            .append("literal default; NPDev's schema-lifecycle executor backfills existing NULL ")
                            .append("rows to that default and enforces NOT NULL after this migration runs, on the ")
                            .append("same boot (LNCH-1 Phase 5; single afterMigrate call site per remediation R2).\n");
                } else {
                    sql.append("-- NOTE: '").append(column).append("' is required by the model but no literal ")
                            .append("default is declared; boot refuses (after this migration runs) until one is ")
                            .append("added or the field is made optional (LNCH-1 Phase 5; remediation R2).\n");
                }
            }
            if (bond.isPresent()) {
                // LNCH-1 P5 (5.3): appendBonds() only emits FK constraints as part of the fresh
                // CREATE TABLE path (V1__) -- a nullable bond column added here to an
                // ALREADY-EXISTING table needs its own FK constraint, or it is just a plain column
                // with no referential integrity.
                Bond resolvedBond = bond.get();
                String constraint = truncate("fk_" + table + "_" + column);
                String constraintSql = "ALTER TABLE " + table
                        + " ADD CONSTRAINT " + constraint
                        + " FOREIGN KEY (" + column + ")"
                        + " REFERENCES " + resolvedBond.targetTable() + " (" + resolvedBond.anchorColumn() + ")"
                        + resolvedBond.onUpdateSqlClause()
                        + " ON DELETE " + resolvedBond.onDeleteSql();
                sql.append(addConstraintIfMissing(engine, table, constraint, constraintSql));
            }
        }
        sql.append("\n");
    }

    /**
     * LNCH-1 P5 (5.3): a non-bond field is always additive-eligible (unchanged). A bond/FK field is
     * additive-eligible only when it is NOT required -- an FK column permits NULLs, so a nullable
     * bond can be added to an existing table exactly like any other nullable field (with its own FK
     * constraint, see {@link #appendAdditiveColumns}). A REQUIRED bond has no literal-default
     * backfill possible in v1 (a bond's "default" would have to reference an existing row's actual
     * key, out of scope), so it stays additive-INeligible; {@code SchemaLifecycleExecutor}
     * (LNCH-1 Phase 5) intercepts that case with a dedicated, itemized refusal instead of letting it
     * fall into the destructive path's generic UNKNOWN bucket. A many-to-many bond has no scalar
     * column at all (its membership lives in a junction table) and is never additive-eligible,
     * regardless of required-ness.
     */
    private static boolean isAdditiveEligible(CompiledConcept concept, CompiledField field,
            Map<String, CompiledConcept> conceptsByName) {
        Optional<Bond> bond = BondModelSupport.resolveBond(concept, field, conceptsByName);
        if (bond.isEmpty()) {
            return true;
        }
        if (bond.get().cardinality() == Cardinality.MANY_TO_MANY) {
            return false;
        }
        return !field.isRequired();
    }

    private static void appendTable(StringBuilder sql, InternalTableDefinition table, DatabaseEngine engine) {
        sql.append("CREATE TABLE IF NOT EXISTS ").append(table.name()).append(" (\n");
        List<String> lines = new ArrayList<>();
        for (InternalColumnDefinition column : table.columns()) {
            StringBuilder line = new StringBuilder("  ")
                    .append(column.name())
                    .append(" ")
                    .append(renderInternalType(column.type(), engine));
            if (column.required()) {
                line.append(" NOT NULL");
            }
            if (!column.defaultExpression().isBlank()) {
                line.append(" DEFAULT ").append(column.defaultExpression());
            }
            lines.add(line.toString());
        }
        lines.add("  PRIMARY KEY (" + String.join(", ", table.primaryKey().columns()) + ")");
        sql.append(String.join(",\n", lines)).append("\n);\n\n");
        for (InternalIndexDefinition index : table.indexes()) {
            sql.append("CREATE ")
                    .append(index.unique() ? "UNIQUE " : "")
                    .append("INDEX IF NOT EXISTS ")
                    .append(index.name())
                    .append(" ON ")
                    .append(table.name())
                    .append(" (")
                    .append(String.join(", ", index.columns()))
                    .append(");\n");
        }
        sql.append("\n");
    }

    // "version" and "tenant_id" are platform-reserved business-table columns: every generated
    // entity gets them implicitly (optimistic concurrency; tenant isolation), regardless of what
    // the model declares. A model field whose column name collides with one of these (e.g. a
    // hand-modeled "tenantId" reference field, as in a pre-platform-tenancy multi-tenant sample)
    // would otherwise silently produce a CREATE TABLE with the same column listed twice -- invalid
    // SQL that fails at the database, not at generation time where the error is actually
    // diagnosable. Fail fast here instead, with a message that tells the model author what to do.
    // REG-64/F10: this guard alone wasn't enough -- it runs downstream of Java compilation, so a
    // colliding field produced a bare javac "duplicate field" error before ever reaching here.
    // Extracted to ReservedColumnNames so EntityEmitter can run the identical check first. Kept
    // here too, unchanged, as defence in depth.
    private static void validateNoReservedColumnCollision(CompiledConcept concept) {
        ReservedColumnNames.validateNoCollision(concept);
    }

    private static void appendBusinessTable(StringBuilder sql, CompiledConcept concept, DatabaseEngine engine,
            Map<String, CompiledConcept> conceptsByName, Set<String> implicitIndexFields) {
        appendBusinessTableShape(sql, concept, engine, conceptsByName);
        appendBusinessTableConstraints(sql, concept, engine, implicitIndexFields);
    }

    /**
     * REG-40 tactical hotfix (schema-engine rebuild plan, Part II): split out of the former
     * monolithic {@code appendBusinessTable} so the R__ repeatable migration can emit the same
     * idempotent {@code CREATE TABLE IF NOT EXISTS} block for a business table that has never
     * existed on an already-running database -- self-healing a missing table on an upgrade instead
     * of failing boot with "Table not found". V1's combined output is unchanged: {@link
     * #appendBusinessTable} still calls this immediately followed by {@link
     * #appendBusinessTableConstraints}, in the same order as before this split.
     */
    private static void appendBusinessTableShape(StringBuilder sql, CompiledConcept concept, DatabaseEngine engine,
            Map<String, CompiledConcept> conceptsByName) {
        String table = SqlIdentifierSupport.tableName(concept);
        List<String> lines = new ArrayList<>();
        String idColumn = null;
        for (CompiledField field : concept.getFields()) {
            Optional<Bond> bond = BondModelSupport.resolveBond(concept, field, conceptsByName);
            // A many-to-many bond has no scalar column; its set lives in a junction table (below).
            if (bond.isPresent() && bond.get().cardinality() == Cardinality.MANY_TO_MANY) {
                continue;
            }
            String column = SqlIdentifierSupport.columnName(field);
            // A bond column takes the bound anchor's SQL type (e.g. a natural-key via sku -> VARCHAR),
            // not the reference default (UUID), so the column matches both the FK target and the
            // generated entity field type (which Hibernate ddl-auto=validate checks at startup).
            String sqlType = bond.isPresent()
                    ? bond.get().effectiveSqlType()
                    : SqlTypeSupport.sqlType(field);
            StringBuilder line = new StringBuilder("  ")
                    .append(column)
                    .append(" ")
                    .append(renderType(sqlType, engine));
            if (field.isRequired() || field.isId()) {
                line.append(" NOT NULL");
            }
            lines.add(line.toString());
            if (field.isId()) {
                idColumn = column;
            }
        }
        if (idColumn == null || idColumn.isBlank()) {
            idColumn = "id";
            lines.add(0, "  id UUID NOT NULL");
        }
        lines.add("  version BIGINT NOT NULL DEFAULT 0");
        // LNCH-16: distinct from "version" above -- that column backs the pre-existing generated-
        // entity checkOptimisticVersion compare (JPA path); row_version backs the real
        // compare-and-increment ConceptGateway/ConceptStore now perform. Kept as separate columns
        // rather than unifying them so this change carries zero coupling/regression risk to the
        // existing mechanism.
        lines.add("  row_version BIGINT NOT NULL DEFAULT 0");
        lines.add("  tenant_id " + renderType("VARCHAR(120)", engine) + " NOT NULL DEFAULT 'default'");
        lines.add("  PRIMARY KEY (" + idColumn + ")");
        sql.append("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n");
        sql.append(String.join(",\n", lines)).append("\n);\n\n");
    }

    /**
     * The constraint/index half of {@link #appendBusinessTableShape}, run separately in R__ so it
     * lands AFTER the additive ALTER TABLE section -- a unique/FK constraint may target a column
     * that section just added to an already-existing table.
     */
    private static void appendBusinessTableConstraints(StringBuilder sql, CompiledConcept concept,
            DatabaseEngine engine, Set<String> implicitIndexFields) {
        String table = SqlIdentifierSupport.tableName(concept);
        for (CompiledField field : concept.getFields()) {
            if (!field.isUnique()) {
                continue;
            }
            String column = SqlIdentifierSupport.columnName(field);
            if (isConnectableAnchor(field) && !field.isId()) {
                // A connectable natural-key anchor must stay GLOBALLY unique: it is a foreign-key
                // target for scalar bonds, and a single-column FK cannot reference a column that is
                // only unique as part of a composite (tenant_id, col) key. So anchors are the one
                // unique kind that is deliberately not tenant-scoped.
                String constraint = truncate("uq_" + table + "_" + column);
                String constraintSql = "ALTER TABLE " + table
                        + " ADD CONSTRAINT " + constraint
                        + " UNIQUE (" + column + ")";
                sql.append(addConstraintIfMissing(engine, table, constraint, constraintSql));
            } else {
                // Ordinary unique fields are unique WITHIN a tenant, not across the whole database:
                // two separate tenants may each have a user with email 'alice@x.com', and a global
                // index would both forbid that and leak cross-tenant existence via 409 collisions.
                // This also aligns the DB constraint with the per-tenant existsUnique pre-check.
                sql.append("CREATE UNIQUE INDEX IF NOT EXISTS ")
                        .append(truncate("ux_" + table + "_" + column))
                        .append(" ON ")
                        .append(table)
                        .append(" (tenant_id, ")
                        .append(column)
                        .append(");\n");
            }
        }

        // LIFT-UNIQUE-P2: compound (multi-field) unique invariants get one composite UNIQUE
        // constraint each, tenant-scoped the same way as ordinary single-field uniques above
        // (two tenants may legitimately share a (tenantId, email) pair otherwise).
        Map<String, CompiledField> fieldsByLowerName = new LinkedHashMap<>();
        for (CompiledField field : concept.getFields()) {
            fieldsByLowerName.put(field.getName().toLowerCase(Locale.ROOT), field);
        }
        for (CompiledInvariant invariant : concept.getInvariants()) {
            if (!"unique".equalsIgnoreCase(invariant.getType()) || invariant.getFields().size() < 2) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            for (String fieldName : invariant.getFields()) {
                CompiledField field = fieldsByLowerName.get(fieldName.toLowerCase(Locale.ROOT));
                if (field != null) {
                    columns.add(SqlIdentifierSupport.columnName(field));
                }
            }
            if (columns.size() < 2) {
                continue;
            }
            String constraint = truncate("uq_" + table + "_" + String.join("_", columns));
            String constraintSql = "ALTER TABLE " + table
                    + " ADD CONSTRAINT " + constraint
                    + " UNIQUE (tenant_id, " + String.join(", ", columns) + ")";
            sql.append(addConstraintIfMissing(engine, table, constraint, constraintSql));
        }

        appendSecondaryIndexes(sql, concept, table, implicitIndexFields);
        appendExplicitIndexes(sql, concept, table, engine);
        sql.append("\n");
    }

    /**
     * LNCH-6: emits an author-declared secondary index for each entry in a concept's {@code
     * indexes:[]} -- the escape hatch for query patterns the implicit panel/query-predicate indexing
     * ({@link #appendSecondaryIndexes}) can't express: a multi-column index, or a field only ever
     * touched by hand-authored SQL/procedures. {@code unique: true} emits a tenant-composite UNIQUE
     * constraint (via the same {@link #addConstraintIfMissing} Postgres-safe guard the compound-unique
     * invariants use) instead of a plain index. Field names that don't resolve to a declared field are
     * dropped rather than failing generation, matching the compound-unique invariant's leniency; an
     * index left with no resolvable columns is skipped entirely.
     */
    private static void appendExplicitIndexes(StringBuilder sql, CompiledConcept concept, String table,
            DatabaseEngine engine) {
        if (concept.getIndexes().isEmpty()) {
            return;
        }
        Map<String, CompiledField> fieldsByLowerName = new LinkedHashMap<>();
        for (CompiledField field : concept.getFields()) {
            fieldsByLowerName.put(field.getName().toLowerCase(Locale.ROOT), field);
        }
        for (CompiledIndex index : concept.getIndexes()) {
            List<String> columns = new ArrayList<>();
            for (String fieldName : index.getFields()) {
                CompiledField field = fieldsByLowerName.get(fieldName.toLowerCase(Locale.ROOT));
                if (field != null) {
                    columns.add(SqlIdentifierSupport.columnName(field));
                }
            }
            if (columns.isEmpty()) {
                continue;
            }
            String baseName = (index.getName() != null && !index.getName().isBlank())
                    ? index.getName()
                    : String.join("_", columns);
            if (index.isUnique()) {
                String constraint = truncate("uqx_" + table + "_" + baseName);
                String constraintSql = "ALTER TABLE " + table
                        + " ADD CONSTRAINT " + constraint
                        + " UNIQUE (tenant_id, " + String.join(", ", columns) + ")";
                sql.append(addConstraintIfMissing(engine, table, constraint, constraintSql));
            } else {
                sql.append("CREATE INDEX IF NOT EXISTS ")
                        .append(truncate("idxx_" + table + "_" + baseName))
                        .append(" ON ")
                        .append(table)
                        .append(" (tenant_id, ")
                        .append(String.join(", ", columns))
                        .append(");\n");
            }
        }
    }

    /**
     * LNCH-6: emits a tenant-composite {@code (tenant_id, col)} secondary index for each model field a
     * panel/query filters, sorts, or joins children by. Fields that are already indexed -- the primary
     * key, and unique fields (which get a {@code ux_} tenant-composite unique index above) -- are
     * skipped so we never emit a redundant index. Index names are truncated to stay within identifier
     * limits, matching the unique-index naming.
     */
    private static void appendSecondaryIndexes(StringBuilder sql, CompiledConcept concept, String table,
            Set<String> indexFields) {
        if (indexFields == null || indexFields.isEmpty()) {
            return;
        }
        Map<String, CompiledField> fieldsByLowerName = new LinkedHashMap<>();
        for (CompiledField field : concept.getFields()) {
            fieldsByLowerName.put(field.getName().toLowerCase(Locale.ROOT), field);
        }
        Set<String> emittedColumns = new LinkedHashSet<>();
        for (String fieldName : indexFields) {
            CompiledField field = fieldsByLowerName.get(fieldName.toLowerCase(Locale.ROOT));
            if (field == null || field.isId() || field.isUnique()) {
                continue;
            }
            String column = SqlIdentifierSupport.columnName(field);
            if (!emittedColumns.add(column.toLowerCase(Locale.ROOT))) {
                continue;
            }
            sql.append("CREATE INDEX IF NOT EXISTS ")
                    .append(truncate("idx_" + table + "_" + column))
                    .append(" ON ")
                    .append(table)
                    .append(" (tenant_id, ")
                    .append(column)
                    .append(");\n");
        }
    }

    /**
     * LNCH-6: gathers, per concept, the field names worth a secondary index because a compiled
     * panel/query touches them in a WHERE, ORDER BY, or parent-child join:
     * <ul>
     *   <li>every query's {@code where} field and {@code orderBy} fields (on the query's concept);</li>
     *   <li>every panel data source's {@code childField} (the FK a nested detail filters children by,
     *       on the child data source's concept).</li>
     * </ul>
     * These are exactly the columns the LNCH-5 push-down constrains, so indexing them turns the
     * generated grid's queries from sequential scans into index scans.
     */
    private static Map<String, Set<String>> collectImplicitIndexFields(CompiledModel model) {
        Map<String, Set<String>> byConcept = new LinkedHashMap<>();
        for (CompiledQuery query : model.getQueries()) {
            String concept = query.concept();
            if (concept == null || concept.isBlank()) {
                continue;
            }
            String whereField = extractWhereField(query.where());
            if (whereField != null) {
                byConcept.computeIfAbsent(concept.toLowerCase(Locale.ROOT), key -> new LinkedHashSet<>()).add(whereField);
            }
            for (String orderBy : query.orderBy()) {
                String field = extractOrderByField(orderBy);
                if (field != null) {
                    byConcept.computeIfAbsent(concept.toLowerCase(Locale.ROOT), key -> new LinkedHashSet<>()).add(field);
                }
            }
        }
        for (CompiledPanel panel : model.getPanels()) {
            for (CompiledPanelDataSource dataSource : panel.dataSources()) {
                String concept = dataSource.concept();
                String childField = dataSource.childField();
                if (concept != null && !concept.isBlank() && childField != null && !childField.isBlank()) {
                    byConcept.computeIfAbsent(concept.toLowerCase(Locale.ROOT), key -> new LinkedHashSet<>())
                            .add(childField.trim());
                }
            }
        }
        return byConcept;
    }

    /** Extracts the single field name on the left of a query {@code where}'s first comparison. */
    private static String extractWhereField(String where) {
        if (where == null || where.isBlank()) {
            return null;
        }
        int cut = where.length();
        for (String op : new String[]{"==", "!=", "<=", ">=", "<", ">", "="}) {
            int index = where.indexOf(op);
            if (index >= 0) {
                cut = Math.min(cut, index);
            }
        }
        if (cut >= where.length()) {
            return null;
        }
        String field = where.substring(0, cut).trim();
        return isIdentifier(field) ? field : null;
    }

    /** Strips a trailing {@code asc}/{@code desc} direction from an {@code orderBy} term. */
    private static String extractOrderByField(String orderBy) {
        if (orderBy == null || orderBy.isBlank()) {
            return null;
        }
        String field = orderBy.trim();
        int space = field.lastIndexOf(' ');
        if (space > 0) {
            String direction = field.substring(space + 1).trim().toLowerCase(Locale.ROOT);
            if (direction.equals("asc") || direction.equals("desc")
                    || direction.equals("ascending") || direction.equals("descending")) {
                field = field.substring(0, space).trim();
            }
        }
        return isIdentifier(field) ? field : null;
    }

    private static boolean isIdentifier(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '_') {
                return false;
            }
        }
        return true;
    }

    /**
     * The full expected column set for a business table (id + version + every non-M2M field,
     * including scalar bond columns), used to detect when the live database has a column the
     * current model no longer declares (a removal — always structural, never safe-additive).
     *
     * <p><b>CONTRACT (LNCH-1 closeout C2):</b> the platform columns this method appends
     * ({@code id} when the concept declares none, then {@code version}, {@code row_version},
     * {@code tenant_id}) are hand-mirrored by
     * {@code SchemaLifecycleExecutor.PLATFORM_MANAGED_COLUMNS} in the RuntimeHost template, which
     * cannot depend on this module. {@code PlatformColumnContractTest} pins the two together — if
     * you add or remove a platform column here, that test fails and tells you what to change.
     * Do not "fix" the failure by editing the test.
     */
    private static List<String> fullColumnNames(CompiledConcept concept, Map<String, CompiledConcept> conceptsByName) {
        List<String> columns = new ArrayList<>();
        boolean hasIdField = false;
        for (CompiledField field : concept.getFields()) {
            Optional<Bond> bond = BondModelSupport.resolveBond(concept, field, conceptsByName);
            if (bond.isPresent() && bond.get().cardinality() == Cardinality.MANY_TO_MANY) {
                continue;
            }
            columns.add(SqlIdentifierSupport.columnName(field));
            if (field.isId()) {
                hasIdField = true;
            }
        }
        if (!hasIdField) {
            columns.add(0, "id");
        }
        columns.add("version");
        columns.add("row_version");
        columns.add("tenant_id");
        return List.copyOf(columns);
    }

    /**
     * The subset of a business table's columns that {@link #appendAdditiveColumns} is able to add
     * to an already-existing table without a destructive recreate (non-bond fields only).
     */
    private static List<String> additiveColumnNames(CompiledConcept concept, Map<String, CompiledConcept> conceptsByName) {
        List<String> columns = new ArrayList<>();
        columns.add("tenant_id");
        columns.add("row_version");
        // LNCH-1 T2 (finding T-B2). Must stay in lockstep with appendAdditiveColumns, which emits an
        // ADD COLUMN IF NOT EXISTS for each of these three. 'version' was previously declared by
        // fullColumnNames but absent here, which made a table missing it un-healable: the runtime
        // itemized it as an UNKNOWN, and an UNKNOWN can only be cleared by a token-gated
        // whole-schema wipe. 'id' is deliberately NOT additive -- it is the primary key, present by
        // construction on any table that exists at all.
        columns.add("version");
        for (CompiledField field : concept.getFields()) {
            if (isAdditiveEligible(concept, field, conceptsByName)) {
                columns.add(SqlIdentifierSupport.columnName(field));
            }
        }
        return List.copyOf(columns);
    }

    /**
     * Column name -> SQL type, for every column {@link #fullColumnNames} lists (id/version/tenant_id
     * included with their fixed platform types). Threaded into the manifest so the runtime schema
     * lifecycle can distinguish "an existing column's type actually changed" from "this is an
     * unrelated remove+add" instead of only ever seeing column names.
     */
    private static Map<String, String> columnTypes(CompiledConcept concept, Map<String, CompiledConcept> conceptsByName) {
        Map<String, String> types = new LinkedHashMap<>();
        boolean hasIdField = false;
        for (CompiledField field : concept.getFields()) {
            Optional<Bond> bond = BondModelSupport.resolveBond(concept, field, conceptsByName);
            if (bond.isPresent() && bond.get().cardinality() == Cardinality.MANY_TO_MANY) {
                continue;
            }
            String sqlType = bond.isPresent() ? bond.get().effectiveSqlType() : SqlTypeSupport.sqlType(field);
            types.put(SqlIdentifierSupport.columnName(field), sqlType);
            if (field.isId()) {
                hasIdField = true;
            }
        }
        if (!hasIdField) {
            types.put("id", "UUID");
        }
        types.put("version", "BIGINT");
        types.put("row_version", "BIGINT");
        types.put("tenant_id", "VARCHAR(120)");
        return types;
    }

    /**
     * New column name -> previous column name, for every field declaring {@code renamedFrom}. Lets
     * the runtime schema lifecycle classify a fingerprint mismatch as a rename instead of an
     * unrelated remove+add when the live database still has the old column and the model now
     * declares the new one in its place.
     */
    private static Map<String, String> columnRenames(CompiledConcept concept, Map<String, CompiledConcept> conceptsByName) {
        Map<String, String> renames = new LinkedHashMap<>();
        for (CompiledField field : concept.getFields()) {
            Optional<Bond> bond = BondModelSupport.resolveBond(concept, field, conceptsByName);
            if (bond.isPresent() && bond.get().cardinality() == Cardinality.MANY_TO_MANY) {
                continue;
            }
            String renamedFrom = field.getRenamedFrom();
            if (renamedFrom != null && !renamedFrom.isBlank()) {
                renames.put(SqlIdentifierSupport.columnName(field), SqlIdentifierSupport.toSnake(renamedFrom));
            }
        }
        return renames;
    }

    /**
     * LNCH-1 P5 (5.2): every column name (bond or not, id excluded implicitly since it is never
     * "missing" from an existing table) whose field is {@code required} in the model. Threaded into
     * the manifest so the runtime schema lifecycle can tell which newly-added additive column needs
     * a backfill-and-NOT-NULL pass, and (for the additive-INeligible case -- a required bond, see
     * {@link #isAdditiveEligible}) which missing column to name in its dedicated refusal instead of
     * the generic destructive-report UNKNOWN bucket.
     */
    private static List<String> requiredColumnNames(CompiledConcept concept, Map<String, CompiledConcept> conceptsByName) {
        List<String> columns = new ArrayList<>();
        for (CompiledField field : concept.getFields()) {
            Optional<Bond> bond = BondModelSupport.resolveBond(concept, field, conceptsByName);
            if (bond.isPresent() && bond.get().cardinality() == Cardinality.MANY_TO_MANY) {
                continue;
            }
            if (field.isRequired()) {
                columns.add(SqlIdentifierSupport.columnName(field));
            }
        }
        return List.copyOf(columns);
    }

    /**
     * LNCH-1 P5 (5.2): column name -> the field's declared literal {@code default}, JSON-encoded
     * (preserves whether the literal is a string/number/boolean, unlike a bare {@code String.valueOf}).
     * Bond/FK fields are excluded -- a bond's "default" would need to reference an existing row's
     * actual key, out of scope for v1 automatic backfill (see {@link #isAdditiveEligible}'s javadoc).
     * The runtime schema-lifecycle executor decodes this back to a typed value (via the same JSON
     * library) and binds it as a JDBC bound parameter for the backfill {@code UPDATE} -- never
     * string-interpolated into SQL text.
     */
    private static Map<String, String> columnDefaultLiterals(CompiledConcept concept, Map<String, CompiledConcept> conceptsByName) {
        Map<String, String> out = new LinkedHashMap<>();
        for (CompiledField field : concept.getFields()) {
            Optional<Bond> bond = BondModelSupport.resolveBond(concept, field, conceptsByName);
            if (bond.isPresent()) {
                continue;
            }
            CompiledSchema schema = field.getSchema();
            if (schema == null || schema.getDefaultValue() == null) {
                continue;
            }
            try {
                out.put(SqlIdentifierSupport.columnName(field), OBJECT_MAPPER.writeValueAsString(schema.getDefaultValue()));
            } catch (Exception ignored) {
                // An unencodable literal should not happen for a JSON-sourced String/Number/Boolean
                // value -- treated as "no literal default available" rather than failing generation.
            }
        }
        return out;
    }

    /**
     * LNCH-1 P5 (5.2): column names that declare a {@code defaultExpression} but no literal
     * {@code default} -- lets the runtime executor's refusal message distinguish "an expression
     * default is declared, but only literal defaults are backfilled automatically in v1" from the
     * plainer "no default declared at all" case.
     */
    private static List<String> expressionDefaultColumnNames(CompiledConcept concept, Map<String, CompiledConcept> conceptsByName) {
        List<String> out = new ArrayList<>();
        for (CompiledField field : concept.getFields()) {
            Optional<Bond> bond = BondModelSupport.resolveBond(concept, field, conceptsByName);
            if (bond.isPresent()) {
                continue;
            }
            CompiledSchema schema = field.getSchema();
            if (schema == null) {
                continue;
            }
            if (schema.getDefaultValue() == null && schema.getDefaultExpression() != null && !schema.getDefaultExpression().isBlank()) {
                out.add(SqlIdentifierSupport.columnName(field));
            }
        }
        return out;
    }

    /**
     * Move 9 B1 (docs/ACCEPTED_BOUNDARIES.md B2): the expression TEXT for every column
     * {@link #expressionDefaultColumnNames} names, so the runtime's dry-run preview/backfill
     * (BackfillPass, RuntimeHost) has something to evaluate -- the SAME {@code ValueExpressionEvaluator}
     * a new row's {@code defaultExpression} already uses (kernel), evaluated here instead against
     * every EXISTING row in a dry run.
     */
    private static Map<String, String> columnDefaultExpressions(CompiledConcept concept, Map<String, CompiledConcept> conceptsByName) {
        Map<String, String> out = new LinkedHashMap<>();
        for (CompiledField field : concept.getFields()) {
            Optional<Bond> bond = BondModelSupport.resolveBond(concept, field, conceptsByName);
            if (bond.isPresent()) {
                continue;
            }
            CompiledSchema schema = field.getSchema();
            if (schema == null || schema.getDefaultValue() != null) {
                continue;
            }
            String expression = schema.getDefaultExpression();
            if (expression != null && !expression.isBlank()) {
                out.put(SqlIdentifierSupport.columnName(field), expression.trim());
            }
        }
        return out;
    }

    /**
     * LNCH-1 P5 (5.1): a single declared unique constraint (single-field, compound-invariant, or
     * explicit-index), independent of whether it targets an existing or brand-new table.
     * {@code tenantScoped} mirrors {@link #appendBusinessTable}'s rule: a connectable natural-key
     * anchor is globally unique (it is an FK target); every other unique kind is scoped to
     * {@code (tenant_id, ...)}.
     */
    public record UniqueConstraintDecl(String name, List<String> columns, boolean tenantScoped) {
    }

    /**
     * LNCH-1 P5 (5.1): the same single-field/compound-invariant/explicit-index unique determination
     * {@link #appendBusinessTable} and {@link #appendExplicitIndexes} use for the fresh-CREATE DDL,
     * recomputed here for the manifest so the runtime executor can pre-check existing data and apply
     * a NEW unique constraint to an ALREADY-EXISTING table (something the fresh-CREATE DDL, which
     * only ever runs via {@code CREATE TABLE IF NOT EXISTS}, never reaches). Deliberately a
     * parallel implementation, not a shared extraction, to avoid touching the DDL-emission code
     * paths those two methods' existing golden tests already cover -- kept honest by
     * {@code SchemaRealizationEmitterUniqueConstraintManifestParityTest}, which cross-checks this
     * method's output against the actual generated SQL for the same concepts.
     */
    private static List<UniqueConstraintDecl> collectUniqueConstraints(CompiledConcept concept, String table) {
        List<UniqueConstraintDecl> specs = new ArrayList<>();
        for (CompiledField field : concept.getFields()) {
            if (!field.isUnique()) {
                continue;
            }
            String column = SqlIdentifierSupport.columnName(field);
            if (isConnectableAnchor(field) && !field.isId()) {
                specs.add(new UniqueConstraintDecl(truncate("uq_" + table + "_" + column), List.of(column), false));
            } else {
                specs.add(new UniqueConstraintDecl(truncate("ux_" + table + "_" + column), List.of(column), true));
            }
        }
        Map<String, CompiledField> fieldsByLowerName = new LinkedHashMap<>();
        for (CompiledField field : concept.getFields()) {
            fieldsByLowerName.put(field.getName().toLowerCase(Locale.ROOT), field);
        }
        for (CompiledInvariant invariant : concept.getInvariants()) {
            if (!"unique".equalsIgnoreCase(invariant.getType()) || invariant.getFields().size() < 2) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            for (String fieldName : invariant.getFields()) {
                CompiledField field = fieldsByLowerName.get(fieldName.toLowerCase(Locale.ROOT));
                if (field != null) {
                    columns.add(SqlIdentifierSupport.columnName(field));
                }
            }
            if (columns.size() < 2) {
                continue;
            }
            specs.add(new UniqueConstraintDecl(truncate("uq_" + table + "_" + String.join("_", columns)), List.copyOf(columns), true));
        }
        for (CompiledIndex index : concept.getIndexes()) {
            if (!index.isUnique()) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            for (String fieldName : index.getFields()) {
                CompiledField field = fieldsByLowerName.get(fieldName.toLowerCase(Locale.ROOT));
                if (field != null) {
                    columns.add(SqlIdentifierSupport.columnName(field));
                }
            }
            if (columns.isEmpty()) {
                continue;
            }
            String baseName = (index.getName() != null && !index.getName().isBlank())
                    ? index.getName()
                    : String.join("_", columns);
            specs.add(new UniqueConstraintDecl(truncate("uqx_" + table + "_" + baseName), List.copyOf(columns), true));
        }
        return specs;
    }

    /**
     * LNCH-1 P2 (2.4): new table name -> previous table name, for a concept declaring
     * {@code renamedFrom} -- but ONLY when the rename implies an actual physical table rename.
     * An explicit {@code tableName} override is a property of the table's physical identity, not
     * of the concept's authoring name (that's the whole point of declaring one), so a concept
     * rename does not by itself change an overridden table's physical name; in that case the old
     * and new physical names are identical and this returns {@code null} (a no-op, not a rename).
     * Reuses {@link SqlIdentifierSupport#tableName(String, String)} for both the "is this an
     * override" check and the old-name derivation -- never re-deriving the
     * toSnakePlural/safeSqlIdentifier convention by hand (guardrail 11).
     */
    private static Map.Entry<String, String> conceptTableRename(CompiledConcept concept) {
        String renamedFrom = concept.getRenamedFrom();
        if (renamedFrom == null || renamedFrom.isBlank()) {
            return null;
        }
        String currentTable = SqlIdentifierSupport.tableName(concept);
        String conventionalCurrentTable = SqlIdentifierSupport.tableName(concept.getName(), null);
        String explicitOverride = currentTable.equals(conventionalCurrentTable) ? null : concept.getTableName();
        String oldTable = SqlIdentifierSupport.tableName(renamedFrom, explicitOverride);
        if (oldTable.equals(currentTable)) {
            return null;
        }
        return Map.entry(currentTable, oldTable);
    }

    private static boolean isConnectableAnchor(CompiledField field) {
        return field != null && "anchor".equalsIgnoreCase(field.getConnectable());
    }

    /**
     * Emits referential integrity for bonds: junction tables for
     * many-to-many bonds, and foreign keys (with the authored ON DELETE policy and, for natural-key
     * anchors, ON UPDATE CASCADE) for scalar bonds. Emitted after all tables and their unique
     * indexes exist so every referenced anchor column is already present.
     */
    private static void appendBonds(StringBuilder sql, CompiledModel model, DatabaseEngine engine,
            Map<String, CompiledConcept> conceptsByName) {
        appendJunctionTableShapes(sql, model, engine);
        appendBondConstraints(sql, model, engine);
    }

    /**
     * REG-40 tactical hotfix (schema-engine rebuild plan, Part II): the CREATE-TABLE half of the
     * former monolithic {@code appendBonds}, extracted so R__ can emit a many-to-many bond's
     * junction table (self-healing a missing one on an upgrade) in the same CREATE-TABLE group as
     * the business tables, ahead of the additive ALTER TABLE section. A junction table has no
     * ALTER-eligible business columns of its own -- only structure -- so it belongs entirely here.
     */
    private static void appendJunctionTableShapes(StringBuilder sql, CompiledModel model, DatabaseEngine engine) {
        for (Bond bond : BondModelSupport.allBonds(model)) {
            if (bond.cardinality() != Cardinality.MANY_TO_MANY) {
                continue;
            }
            CompiledField sourceId = BondModelSupport.idField(bond.sourceConcept());
            String junctionTable = bond.junctionTable();
            String sourceColumn = SqlIdentifierSupport.sourceJunctionColumn(sourceId);
            String targetColumn = SqlIdentifierSupport.targetJunctionColumn(bond.anchorField());
            sql.append("CREATE TABLE IF NOT EXISTS ").append(junctionTable).append(" (\n")
                    .append("  ").append(sourceColumn).append(" ").append(renderType(SqlTypeSupport.sqlType(sourceId), engine)).append(" NOT NULL,\n")
                    .append("  ").append(targetColumn).append(" ").append(renderType(bond.effectiveSqlType(), engine)).append(" NOT NULL,\n")
                    .append("  PRIMARY KEY (").append(sourceColumn).append(", ").append(targetColumn).append(")\n")
                    .append(");\n");
            sql.append("CREATE INDEX IF NOT EXISTS ")
                    .append(SqlIdentifierSupport.safeSqlIdentifier("idx_" + junctionTable + "_" + sourceColumn))
                    .append(" ON ").append(junctionTable).append(" (").append(sourceColumn).append(");\n");
            sql.append("CREATE INDEX IF NOT EXISTS ")
                    .append(SqlIdentifierSupport.safeSqlIdentifier("idx_" + junctionTable + "_" + targetColumn))
                    .append(" ON ").append(junctionTable).append(" (").append(targetColumn).append(");\n");
        }
    }

    /**
     * The FK-constraint half of the former monolithic {@code appendBonds} (junction-table FKs, then
     * scalar N:1/1:1 FKs), extracted so R__ can emit it AFTER both the CREATE-TABLE group ({@link
     * #appendBusinessTableShape}, {@link #appendJunctionTableShapes}) and the additive ALTER TABLE
     * section -- a bond FK may reference a column either group just created or restored.
     */
    private static void appendBondConstraints(StringBuilder sql, CompiledModel model, DatabaseEngine engine) {
        List<Bond> bonds = BondModelSupport.allBonds(model);
        if (bonds.isEmpty()) {
            return;
        }

        // Junction-table FKs (N:M bonds).
        for (Bond bond : bonds) {
            if (bond.cardinality() != Cardinality.MANY_TO_MANY) {
                continue;
            }
            CompiledField sourceId = BondModelSupport.idField(bond.sourceConcept());
            String junctionTable = bond.junctionTable();
            String sourceColumn = SqlIdentifierSupport.sourceJunctionColumn(sourceId);
            String targetColumn = SqlIdentifierSupport.targetJunctionColumn(bond.anchorField());
            // Source-side FK is always CASCADE: a junction row is membership owned by its source.
            String sourceConstraint = SqlIdentifierSupport.safeSqlIdentifier("fk_" + junctionTable + "_" + sourceColumn);
            String sourceConstraintSql = "ALTER TABLE " + junctionTable
                    + " ADD CONSTRAINT " + sourceConstraint
                    + " FOREIGN KEY (" + sourceColumn + ")"
                    + " REFERENCES " + bond.sourceTable() + " (" + SqlIdentifierSupport.columnName(sourceId) + ")"
                    + " ON DELETE CASCADE";
            sql.append(addConstraintIfMissing(engine, junctionTable, sourceConstraint, sourceConstraintSql));
            String targetConstraint = SqlIdentifierSupport.safeSqlIdentifier("fk_" + junctionTable + "_" + targetColumn);
            String targetConstraintSql = "ALTER TABLE " + junctionTable
                    + " ADD CONSTRAINT " + targetConstraint
                    + " FOREIGN KEY (" + targetColumn + ")"
                    + " REFERENCES " + bond.targetTable() + " (" + bond.anchorColumn() + ")"
                    + bond.onUpdateSqlClause()
                    + " ON DELETE " + bond.onDeleteSql();
            sql.append(addConstraintIfMissing(engine, junctionTable, targetConstraint, targetConstraintSql)).append("\n");
        }

        // Foreign keys for scalar (N:1 / 1:1) bonds.
        for (Bond bond : bonds) {
            if (bond.cardinality() == Cardinality.MANY_TO_MANY) {
                continue;
            }
            String constraint = SqlIdentifierSupport.safeSqlIdentifier("fk_" + bond.sourceTable() + "_" + bond.sourceColumn());
            String constraintSql = "ALTER TABLE " + bond.sourceTable()
                    + " ADD CONSTRAINT " + constraint
                    + " FOREIGN KEY (" + bond.sourceColumn() + ")"
                    + " REFERENCES " + bond.targetTable() + " (" + bond.anchorColumn() + ")"
                    + bond.onUpdateSqlClause()
                    + " ON DELETE " + bond.onDeleteSql();
            sql.append(addConstraintIfMissing(engine, bond.sourceTable(), constraint, constraintSql));
        }
        sql.append("\n");
    }

    private static String addConstraintIfMissing(
            DatabaseEngine engine,
            String tableName,
            String constraintName,
            String addConstraintSql
    ) {
        String statement = addConstraintSql.endsWith(";") ? addConstraintSql : addConstraintSql + ";";
        if (engine != DatabaseEngine.POSTGRES) {
            // REG-38: this lands in R__npdev_schema_additive_columns.sql, a Flyway *repeatable*
            // migration that re-runs whenever its checksum changes (any model edit regenerates it).
            // A bare "ADD CONSTRAINT" is not idempotent -- the re-run against a DB that already has
            // the constraint fails with "Constraint already exists" and refuses the whole boot. H2
            // supports "DROP CONSTRAINT IF EXISTS", so drop-then-add makes the statement idempotent
            // the same way the Postgres branch below is (via its IF-NOT-EXISTS catalog guard).
            return "ALTER TABLE " + tableName + " DROP CONSTRAINT IF EXISTS " + constraintName + ";\n"
                    + statement + "\n";
        }
        // INFORMATION_SCHEMA.TABLE_CONSTRAINTS is standard SQL available in both PostgreSQL
        // and H2 PostgreSQL-compatibility mode. pg_constraint/pg_class/pg_namespace are
        // PostgreSQL-only system catalogs and must not be used even in the Postgres-only path,
        // to keep both emitters byte-consistent and avoid drift when switching engines.
        return """
                DO $$
                BEGIN
                  IF NOT EXISTS (
                    SELECT 1
                    FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                    WHERE CONSTRAINT_NAME = '%s'
                      AND TABLE_NAME = '%s'
                      AND TABLE_SCHEMA = current_schema()
                  ) THEN
                    %s
                  END IF;
                END $$;
                """.formatted(
                sqlLiteral(constraintName),
                sqlLiteral(tableName),
                statement
        );
    }

    private static String sqlLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    /**
     * LNCH-1 P6 (task 6.1): the per-concept manifest-shaped metadata computation extracted
     * verbatim from {@link #emitManifest}'s former inline loop (behavior-preserving refactor --
     * {@code emitManifest} now calls this method instead of duplicating it, so the two callers can
     * never independently drift). Public so
     * {@code com.npdev.generator.schemaevolution.MigrationPlanEmitter} can call the SAME production
     * computation for BOTH the new and the previous compiled model when building a migration-plan
     * preview, instead of re-deriving additive-eligibility / unique-constraint / required-default
     * determination by hand (guardrail 11's "reuse the production method" discipline, applied to
     * this generator-only concern -- there is no RuntimeHost equivalent to share via the DSL module
     * here, since RuntimeHost never computes this FROM a model; it only ever reads it back out of
     * the manifest this method feeds).
     *
     * <p>Always computes for every concept in {@code model} regardless of
     * {@code GeneratedDatabasePlan#createBusinessTables()} -- that flag is a caller concern
     * ({@link #emitManifest} substitutes {@link BusinessTableMetadata#empty()} when it is false;
     * {@code MigrationPlanEmitter} makes its own equivalent decision against whichever plan/db
     * definition it was given).
     */
    public static BusinessTableMetadata computeBusinessTableMetadata(CompiledModel model) {
        List<String> businessTables = model.getConcepts().stream().map(SqlIdentifierSupport::tableName).toList();
        Map<String, List<String>> businessTableColumns = new LinkedHashMap<>();
        Map<String, List<String>> businessTableAdditiveColumns = new LinkedHashMap<>();
        Map<String, Map<String, String>> businessTableColumnTypes = new LinkedHashMap<>();
        Map<String, Map<String, String>> businessTableRenamedColumns = new LinkedHashMap<>();
        Map<String, String> businessTableRenames = new LinkedHashMap<>();
        Map<String, List<String>> businessTableRequiredColumns = new LinkedHashMap<>();
        Map<String, Map<String, String>> businessTableColumnDefaultLiterals = new LinkedHashMap<>();
        Map<String, List<String>> businessTableExpressionDefaultColumns = new LinkedHashMap<>();
        Map<String, Map<String, String>> businessTableColumnDefaultExpressions = new LinkedHashMap<>();
        Map<String, List<UniqueConstraintDecl>> businessTableUniqueConstraints = new LinkedHashMap<>();
        Map<String, List<ForeignKeyDecl>> businessTableForeignKeys = new LinkedHashMap<>();
        Map<String, List<IndexDecl>> businessTableIndexes = new LinkedHashMap<>();

        Map<String, CompiledConcept> conceptsByName = BondModelSupport.conceptsByName(model);
        // REG-129: same computation appendBusinessTableConstraints's two callers already use to
        // feed appendSecondaryIndexes -- collectIndexes needs the identical per-concept field set
        // so businessTableIndexes describes exactly what that DDL creates, not a narrower view of it.
        Map<String, Set<String>> implicitIndexFields = collectImplicitIndexFields(model);
        for (CompiledConcept concept : model.getConcepts()) {
            String table = SqlIdentifierSupport.tableName(concept);
            businessTableColumns.put(table, fullColumnNames(concept, conceptsByName));
            businessTableAdditiveColumns.put(table, additiveColumnNames(concept, conceptsByName));
            businessTableColumnTypes.put(table, columnTypes(concept, conceptsByName));
            Map<String, String> renames = columnRenames(concept, conceptsByName);
            if (!renames.isEmpty()) {
                businessTableRenamedColumns.put(table, renames);
            }
            Map.Entry<String, String> tableRename = conceptTableRename(concept);
            if (tableRename != null) {
                businessTableRenames.put(tableRename.getKey(), tableRename.getValue());
            }
            List<String> requiredColumns = requiredColumnNames(concept, conceptsByName);
            if (!requiredColumns.isEmpty()) {
                businessTableRequiredColumns.put(table, requiredColumns);
            }
            Map<String, String> defaultLiterals = columnDefaultLiterals(concept, conceptsByName);
            if (!defaultLiterals.isEmpty()) {
                businessTableColumnDefaultLiterals.put(table, defaultLiterals);
            }
            List<String> expressionDefaults = expressionDefaultColumnNames(concept, conceptsByName);
            if (!expressionDefaults.isEmpty()) {
                businessTableExpressionDefaultColumns.put(table, expressionDefaults);
            }
            // Move 9 B1 (docs/ACCEPTED_BOUNDARIES.md B2): the expression TEXT itself, not just which
            // columns declare one -- expressionDefaultColumnNames only ever carried the column name,
            // leaving the runtime's dry-run preview/backfill with nothing to actually evaluate.
            Map<String, String> defaultExpressions = columnDefaultExpressions(concept, conceptsByName);
            if (!defaultExpressions.isEmpty()) {
                businessTableColumnDefaultExpressions.put(table, defaultExpressions);
            }
            List<UniqueConstraintDecl> uniqueConstraints = collectUniqueConstraints(concept, table);
            if (!uniqueConstraints.isEmpty()) {
                businessTableUniqueConstraints.put(table, uniqueConstraints);
            }
            // SER-G8: the FKs and indexes this concept's DDL creates, recorded in the manifest so the
            // runtime can VERIFY them (ExternallyManaged full-shape check) instead of being blind to
            // the FK/index dimension entirely. Same source of truth as the DDL emitted above.
            List<ForeignKeyDecl> foreignKeys = collectForeignKeys(concept, conceptsByName);
            if (!foreignKeys.isEmpty()) {
                businessTableForeignKeys.put(table, foreignKeys);
            }
            List<IndexDecl> indexes = collectIndexes(concept, conceptsByName, uniqueConstraints,
                    implicitIndexFields.getOrDefault(concept.getName().toLowerCase(Locale.ROOT), Set.of()));
            if (!indexes.isEmpty()) {
                businessTableIndexes.put(table, indexes);
            }
        }

        return new BusinessTableMetadata(
                businessTables,
                businessTableColumns,
                businessTableAdditiveColumns,
                businessTableColumnTypes,
                businessTableRenamedColumns,
                businessTableRenames,
                businessTableRequiredColumns,
                businessTableColumnDefaultLiterals,
                businessTableExpressionDefaultColumns,
                businessTableUniqueConstraints,
                businessTableForeignKeys,
                businessTableIndexes,
                businessTableColumnDefaultExpressions
        );
    }

    /** SER-G8: a foreign key the model declares, for manifest emission. Carries no NAME — the runtime
     *  matches by column set + referenced table, because constraint names are engine-generated. */
    public record ForeignKeyDecl(List<String> columns, String referencedTable, List<String> referencedColumns) {
    }

    /** SER-G8: an index the model declares, for manifest emission. Carries no NAME, for the same reason
     *  as {@link ForeignKeyDecl}. */
    public record IndexDecl(List<String> columns, boolean unique) {
    }

    /** See {@link #computeBusinessTableMetadata}. */
    public record BusinessTableMetadata(
            List<String> businessTables,
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, Map<String, String>> businessTableRenamedColumns,
            Map<String, String> businessTableRenames,
            Map<String, List<String>> businessTableRequiredColumns,
            Map<String, Map<String, String>> businessTableColumnDefaultLiterals,
            Map<String, List<String>> businessTableExpressionDefaultColumns,
            Map<String, List<UniqueConstraintDecl>> businessTableUniqueConstraints,
            Map<String, List<ForeignKeyDecl>> businessTableForeignKeys,
            Map<String, List<IndexDecl>> businessTableIndexes,
            // Move 9 B1 (docs/ACCEPTED_BOUNDARIES.md B2): added LAST, same SER-G8 convention as the FK/
            // index maps above -- absent from an older generated manifest simply means "no expression
            // defaults to preview," not a behavior change for any app built before this.
            Map<String, Map<String, String>> businessTableColumnDefaultExpressions
    ) {
        static BusinessTableMetadata empty() {
            return new BusinessTableMetadata(List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    /** SER-G8: every bond field on this concept becomes one FK — the SAME (column -&gt; targetTable.anchor)
     *  relationship {@code appendBondConstraints}/the additive path emit as DDL. */
    private static List<ForeignKeyDecl> collectForeignKeys(
            CompiledConcept concept, Map<String, CompiledConcept> conceptsByName) {
        List<ForeignKeyDecl> foreignKeys = new ArrayList<>();
        for (CompiledField field : concept.getFields()) {
            Optional<Bond> bond = BondModelSupport.resolveBond(concept, field, conceptsByName);
            if (bond.isEmpty()) {
                continue;
            }
            Bond resolved = bond.get();
            foreignKeys.add(new ForeignKeyDecl(
                    List.of(SqlIdentifierSupport.columnName(field)),
                    resolved.targetTable(),
                    List.of(resolved.anchorColumn())));
        }
        return List.copyOf(foreignKeys);
    }

    /**
     * SER-G8: the indexes this concept's DDL creates. Deliberately does NOT include implicit
     * primary-key indexes: the runtime treats a live PK over the same columns as satisfying a
     * declared index, and the model never needs to ask for one.
     *
     * <p>REG-129: this used to describe only "one per unique constraint and one per bond column" --
     * two of the THREE categories {@link #appendBusinessTableConstraints}'s DDL actually creates.
     * The other two, {@link #appendSecondaryIndexes} (LNCH-6's implicit panel/query-driven
     * indexes, {@code idx_}-prefixed) and {@link #appendExplicitIndexes} (author-declared {@code
     * concept.indexes[]}, {@code idxx_}/{@code uqx_}-prefixed), were invisible to
     * {@code businessTableIndexes} -- confirmed on WmsOffice's live database: all 17 "FOREIGN"
     * findings B3's surplus classifier reported were exactly these, real NPDev-created indexes the
     * manifest simply never told it about (S8 Wave 2's own hard-stop calibration,
     * `reg129-manifest-index-drift.py`). Both are now folded in here, using the SAME
     * field-eligibility/resolution rules as their DDL-emitting counterparts, so the manifest never
     * drifts from what the DDL above it in this same class actually creates.
     */
    private static List<IndexDecl> collectIndexes(
            CompiledConcept concept, Map<String, CompiledConcept> conceptsByName,
            List<UniqueConstraintDecl> uniqueConstraints, Set<String> implicitIndexFields) {
        List<IndexDecl> indexes = new ArrayList<>();
        for (UniqueConstraintDecl unique : uniqueConstraints) {
            indexes.add(new IndexDecl(List.copyOf(unique.columns()), true));
        }
        for (CompiledField field : concept.getFields()) {
            if (BondModelSupport.resolveBond(concept, field, conceptsByName).isPresent()) {
                indexes.add(new IndexDecl(List.of(SqlIdentifierSupport.columnName(field)), false));
            }
        }

        Map<String, CompiledField> fieldsByLowerName = new LinkedHashMap<>();
        for (CompiledField field : concept.getFields()) {
            fieldsByLowerName.put(field.getName().toLowerCase(Locale.ROOT), field);
        }

        // LNCH-6 secondary indexes (appendSecondaryIndexes): same skip-id/skip-unique/dedupe rule.
        if (implicitIndexFields != null && !implicitIndexFields.isEmpty()) {
            Set<String> emittedColumns = new LinkedHashSet<>();
            for (String fieldName : implicitIndexFields) {
                CompiledField field = fieldsByLowerName.get(fieldName.toLowerCase(Locale.ROOT));
                if (field == null || field.isId() || field.isUnique()) {
                    continue;
                }
                String column = SqlIdentifierSupport.columnName(field);
                if (emittedColumns.add(column.toLowerCase(Locale.ROOT))) {
                    indexes.add(new IndexDecl(List.of(column), false));
                }
            }
        }

        // Author-declared concept.indexes[] (appendExplicitIndexes) -- NON-unique half only. A
        // unique explicit index is already collected above via uniqueConstraints:
        // collectUniqueConstraints (this class, ~line 945) independently walks concept.getIndexes()
        // for index.isUnique() and adds a uqx_-prefixed UniqueConstraintDecl -- the exact same
        // "uqx_" naming appendExplicitIndexes' unique branch uses for its DDL. Re-adding it here
        // too would double the manifest entry for the SAME real constraint.
        for (CompiledIndex index : concept.getIndexes()) {
            if (index.isUnique()) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            for (String fieldName : index.getFields()) {
                CompiledField field = fieldsByLowerName.get(fieldName.toLowerCase(Locale.ROOT));
                if (field != null) {
                    columns.add(SqlIdentifierSupport.columnName(field));
                }
            }
            if (!columns.isEmpty()) {
                indexes.add(new IndexDecl(List.copyOf(columns), false));
            }
        }

        return List.copyOf(indexes);
    }

    private static void emitManifest(
            CompiledModel model,
            Path resourcesRoot,
            GeneratedDatabasePlan plan,
            Path modelSourcePath,
            List<String> migrationPlanItemStableStrings,
            String destructiveAcknowledgmentToken
    ) throws Exception {
        List<String> internalTables = plan.createInternalTables()
                ? NpdevInternalTables.all().stream().map(InternalTableDefinition::name).toList()
                : List.of();
        BusinessTableMetadata businessMetadata = plan.createBusinessTables()
                ? computeBusinessTableMetadata(model)
                : BusinessTableMetadata.empty();
        List<String> businessTables = businessMetadata.businessTables();
        Map<String, List<String>> businessTableColumns = businessMetadata.businessTableColumns();
        Map<String, List<String>> businessTableAdditiveColumns = businessMetadata.businessTableAdditiveColumns();
        Map<String, Map<String, String>> businessTableColumnTypes = businessMetadata.businessTableColumnTypes();
        Map<String, Map<String, String>> businessTableRenamedColumns = businessMetadata.businessTableRenamedColumns();
        Map<String, String> businessTableRenames = businessMetadata.businessTableRenames();
        Map<String, List<String>> businessTableRequiredColumns = businessMetadata.businessTableRequiredColumns();
        Map<String, Map<String, String>> businessTableColumnDefaultLiterals = businessMetadata.businessTableColumnDefaultLiterals();
        Map<String, List<String>> businessTableExpressionDefaultColumns = businessMetadata.businessTableExpressionDefaultColumns();
        Map<String, Map<String, String>> businessTableColumnDefaultExpressions = businessMetadata.businessTableColumnDefaultExpressions();
        Map<String, List<Map<String, Object>>> businessTableUniqueConstraints = new LinkedHashMap<>();
        for (Map.Entry<String, List<UniqueConstraintDecl>> entry : businessMetadata.businessTableUniqueConstraints().entrySet()) {
            List<Map<String, Object>> encoded = new ArrayList<>();
            for (UniqueConstraintDecl decl : entry.getValue()) {
                Map<String, Object> encodedDecl = new LinkedHashMap<>();
                encodedDecl.put("name", decl.name());
                encodedDecl.put("columns", decl.columns());
                encodedDecl.put("tenantScoped", decl.tenantScoped());
                encoded.add(encodedDecl);
            }
            businessTableUniqueConstraints.put(entry.getKey(), encoded);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaRealizationVersion", "1");
        manifest.put("engine", plan.engine().externalName());
        manifest.put("storageMode", plan.storageMode());
        manifest.put("physicalDatabase", plan.jdbc());
        manifest.put("database", databaseIdentity(plan));
        manifest.put("schemaFingerprint", plan.schemaFingerprint());
        // Insertion-ordered, not Map.of(...): java.util.Map.of with 2+ entries produces an
        // ImmutableCollections.MapN whose iteration order is randomized per-JVM by
        // ImmutableCollections.SALT, which Jackson would otherwise serialize in that varying order
        // -- the GATE-DET-1 byte-nondeterminism mechanism. This emitter's OBJECT_MAPPER happens to
        // enable ORDER_MAP_ENTRIES_BY_KEYS, which today re-sorts every map (nested ones included) by
        // key at write time and so masks the hazard for schema-realization-manifest.json -- unlike
        // FinalAppAssembler's plain mapper, whose identical storageBoundary Map.of did flip run to
        // run (the original T10 bug). We do NOT rely on that global flag to compensate for a
        // per-site Map.of: this manifest is what SchemaLifecycleExecutor reads at boot, so keep each
        // nested object insertion-ordered and the determinism guarantee local.
        Map<String, Object> schemaLifecycle = new LinkedHashMap<>();
        schemaLifecycle.put("strategy", plan.schemaLifecycle().strategy().externalName());
        schemaLifecycle.put("allowDestructiveRecreate", plan.schemaLifecycle().allowDestructiveRecreate());
        schemaLifecycle.put("scope", plan.schemaLifecycle().scope());
        schemaLifecycle.put("destructiveRecreateConfirmation", plan.schemaLifecycle().destructiveRecreateConfirmation());
        // REG-7.1: whether NPDev owns this app's schema DDL. Absent from every manifest emitted
        // before this field existed -- SchemaLifecycleExecutor#loadManifest defaults a missing key to
        // "NpdevManaged", today's only behavior, so a pre-existing manifest is unaffected.
        schemaLifecycle.put("ownership", plan.schemaLifecycle().ownership().externalName());
        manifest.put("schemaLifecycle", schemaLifecycle);
        manifest.put("internalTables", internalTables);
        manifest.put("businessTables", businessTables);
        manifest.put("businessTableColumns", businessTableColumns);
        manifest.put("businessTableAdditiveColumns", businessTableAdditiveColumns);
        manifest.put("businessTableColumnTypes", businessTableColumnTypes);
        manifest.put("businessTableRenamedColumns", businessTableRenamedColumns);
        manifest.put("businessTableRenames", businessTableRenames);
        // LNCH-1 Phase 5.
        manifest.put("businessTableRequiredColumns", businessTableRequiredColumns);
        manifest.put("businessTableColumnDefaultLiterals", businessTableColumnDefaultLiterals);
        manifest.put("businessTableExpressionDefaultColumns", businessTableExpressionDefaultColumns);
        // Move 9 B1 (docs/ACCEPTED_BOUNDARIES.md B2): the expression TEXT itself (see
        // columnDefaultExpressions's javadoc) -- absent from every manifest emitted before this,
        // which SchemaManifestLoader defaults to an empty map, so a pre-existing app is unaffected.
        manifest.put("businessTableColumnDefaultExpressions", businessTableColumnDefaultExpressions);
        manifest.put("businessTableUniqueConstraints", businessTableUniqueConstraints);
        // SER-G8: the model's declared FKs/indexes, encoded name-lessly (the runtime matches by column
        // set, since constraint/index names are engine-generated and differ across H2/Postgres).
        Map<String, List<Map<String, Object>>> encodedForeignKeys = new LinkedHashMap<>();
        for (Map.Entry<String, List<ForeignKeyDecl>> entry : businessMetadata.businessTableForeignKeys().entrySet()) {
            List<Map<String, Object>> encoded = new ArrayList<>();
            for (ForeignKeyDecl decl : entry.getValue()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("columns", decl.columns());
                one.put("referencedTable", decl.referencedTable());
                one.put("referencedColumns", decl.referencedColumns());
                encoded.add(one);
            }
            encodedForeignKeys.put(entry.getKey(), encoded);
        }
        Map<String, List<Map<String, Object>>> encodedIndexes = new LinkedHashMap<>();
        for (Map.Entry<String, List<IndexDecl>> entry : businessMetadata.businessTableIndexes().entrySet()) {
            List<Map<String, Object>> encoded = new ArrayList<>();
            for (IndexDecl decl : entry.getValue()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("columns", decl.columns());
                one.put("unique", decl.unique());
                encoded.add(one);
            }
            encodedIndexes.put(entry.getKey(), encoded);
        }
        manifest.put("businessTableForeignKeys", encodedForeignKeys);
        manifest.put("businessTableIndexes", encodedIndexes);
        // LNCH-1 Phase 6 (task 6.3): the destructive-item stable strings from a migration plan
        // computed THIS generation pass (empty when none was computed -- see this method's caller,
        // SchemaRealizationEmitter#emit's 5-arg overload). Lets the runtime executor's agreement
        // check print both "what the plan expected" and "what it found live at boot" when they
        // disagree (model drift between plan-generation time and boot time).
        manifest.put("migrationPlanItemStableStrings", migrationPlanItemStableStrings);
        // LNCH-1 Phase 6 (task 6.2b): the itemized destructive-acknowledgment token, threaded
        // verbatim from GeneratorMain's optional --destructiveAcknowledgment CLI flag. "" (the
        // default) never equals a real computed token, so an app generated without this flag is
        // unaffected -- SchemaLifecycleExecutor's Phase 4 token check still refuses exactly as
        // before, or an operator can now supply this at generation time instead of hand-editing the
        // manifest.
        manifest.put("destructiveAcknowledgment", destructiveAcknowledgmentToken == null ? "" : destructiveAcknowledgmentToken);
        // Insertion-ordered, not Map.of(...) -- see the schemaLifecycle comment above for the
        // ImmutableCollections.SALT mechanism this avoids.
        Map<String, Object> sourceOfTruth = new LinkedHashMap<>();
        sourceOfTruth.put("internal", resolveInternalSchemaSourcePath(plan.definitionPath()).toString());
        sourceOfTruth.put("business", modelSourcePath == null ? "" : modelSourcePath.toAbsolutePath().normalize().toString());
        sourceOfTruth.put("database", plan.definitionPath().toString());
        manifest.put("sourceOfTruth", sourceOfTruth);
        manifest.put("fingerprintInputs", plan.fingerprintInputs());

        Path manifestPath = resourcesRoot.resolve("npdev").resolve("db").resolve("schema-realization-manifest.json");
        Files.createDirectories(manifestPath.getParent());
        Files.writeString(
                manifestPath,
                OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
    }

    private static void emitApplicationProperties(Path resourcesRoot, GeneratedDatabasePlan plan) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("npdev.database.engine=").append(plan.engine().externalName()).append("\n");
        out.append("npdev.database.requested-name=").append(plan.requestedDatabaseName()).append("\n");
        out.append("npdev.database.resolved-name=").append(plan.resolvedDatabaseName()).append("\n");
        out.append("npdev.database.name-source=").append(plan.databaseNameSource()).append("\n");
        out.append("npdev.database.instance-id=").append(plan.databaseInstanceId()).append("\n");
        out.append("npdev.database.data-root=").append(plan.resolvedDataRoot()).append("\n");
        out.append("npdev.storage.mode=").append(plan.storageMode()).append("\n");
        out.append("npdev.schema.fingerprint=").append(plan.schemaFingerprint()).append("\n");
        out.append("npdev.schema.lifecycle.strategy=").append(plan.schemaLifecycle().strategy().externalName()).append("\n");
        out.append("npdev.schema.lifecycle.allow-destructive-recreate=").append(plan.schemaLifecycle().allowDestructiveRecreate()).append("\n");
        out.append("npdev.schema.lifecycle.scope=").append(plan.schemaLifecycle().scope()).append("\n");
        out.append("npdev.schema.lifecycle.destructive-recreate-confirmation=")
                .append(plan.schemaLifecycle().destructiveRecreateConfirmation()).append("\n");
        out.append("npdev.schema.lifecycle.ownership=").append(plan.schemaLifecycle().ownership().externalName()).append("\n");
        if (plan.jdbc()) {
            out.append("spring.datasource.url=").append(plan.jdbcUrl()).append("\n");
            out.append("spring.datasource.driver-class-name=").append(plan.driverClassName()).append("\n");
            out.append("spring.datasource.username=").append(plan.username()).append("\n");
            out.append("spring.datasource.password=").append(plan.password()).append("\n");
            out.append("spring.flyway.enabled=true\n");
            out.append("spring.flyway.locations=classpath:db/schema-realization\n");
            out.append("spring.jpa.hibernate.ddl-auto=validate\n");
        } else {
            out.append("spring.autoconfigure.exclude=")
                    .append("org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,")
                    .append("org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,")
                    .append("org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,")
                    .append("org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration\n");
            out.append("spring.flyway.enabled=false\n");
            out.append("spring.jpa.hibernate.ddl-auto=none\n");
        }
        Files.writeString(resourcesRoot.resolve("application-npdev-db.properties"), out.toString(), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> databaseIdentity(GeneratedDatabasePlan plan) {
        Map<String, Object> database = new LinkedHashMap<>();
        database.put("requestedDatabaseName", plan.requestedDatabaseName());
        database.put("resolvedDatabaseName", plan.resolvedDatabaseName());
        database.put("databaseNameSource", plan.databaseNameSource());
        database.put("resolvedDataRoot", plan.resolvedDataRoot());
        database.put("databaseInstanceId", plan.databaseInstanceId());
        database.put("containerName", plan.containerName());
        database.put("host", plan.host());
        database.put("hostPort", plan.hostPort());
        database.put("jdbcUrl", plan.jdbcUrl());
        // Insertion-ordered, not Map.of(...) -- see the schemaLifecycle comment in
        // writeSchemaRealizationManifest for the ImmutableCollections.SALT mechanism this avoids.
        // This nested object lands in the same schema-realization-manifest.json.
        Map<String, Object> dbeaver = new LinkedHashMap<>();
        dbeaver.put("host", plan.dbeaverHost());
        dbeaver.put("port", plan.dbeaverPort());
        dbeaver.put("database", plan.dbeaverDatabase());
        dbeaver.put("username", plan.dbeaverUsername());
        dbeaver.put("ssl", "disabled");
        database.put("dbeaver", dbeaver);
        return database;
    }

    private static Map<String, Object> storeMetadata(CompiledModel model, GeneratedDatabasePlan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", plan.engine().externalName());
        out.put("storageMode", plan.storageMode());
        out.put("schemaFingerprint", plan.schemaFingerprint());
        out.put("internalStores", NpdevInternalTables.all().stream().map(InternalTableDefinition::name).toList());
        out.put("businessStores", model.getConcepts().stream().map(UserDatabaseDefinitionLoader::safeTable).toList());
        return out;
    }

    private static String renderType(String sqlType, DatabaseEngine engine) {
        if (sqlType == null || sqlType.isBlank()) {
            return "VARCHAR(255)";
        }
        String normalized = sqlType.trim();
        if (engine == DatabaseEngine.H2_LOCAL || engine == DatabaseEngine.H2_SERVER) {
            if ("JSONB".equalsIgnoreCase(normalized) || "JSON".equalsIgnoreCase(normalized)) {
                return "JSON";
            }
            if ("TIMESTAMP WITH TIME ZONE".equalsIgnoreCase(normalized)) {
                return "TIMESTAMP WITH TIME ZONE";
            }
        }
        return normalized;
    }

    private static String renderInternalType(InternalColumnType type, DatabaseEngine engine) {
        if (type == null) {
            return "VARCHAR(255)";
        }
        return switch (type) {
            case TEXT, LARGE_TEXT, JSON_DOCUMENT -> "TEXT";
            case TIMESTAMP -> "TIMESTAMP";
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
        };
    }

    private static String truncate(String value) {
        return SqlIdentifierSupport.safeSqlIdentifier(value);
    }

    private static Path resolveInternalSchemaSourcePath(Path hint) {
        Path current = hint == null ? Path.of("").toAbsolutePath().normalize() : hint.toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("NPDev_General")
                    .resolve("NPDevKernel")
                    .resolve("kernel")
                    .resolve("src")
                    .resolve("main")
                    .resolve("java")
                    .resolve("com")
                    .resolve("npdev")
                    .resolve("kernel")
                    .resolve("dbschema");
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
            candidate = current.resolve("NPDevKernel")
                    .resolve("kernel")
                    .resolve("src")
                    .resolve("main")
                    .resolve("java")
                    .resolve("com")
                    .resolve("npdev")
                    .resolve("kernel")
                    .resolve("dbschema");
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
            current = current.getParent();
        }
        return Path.of("NPDevKernel")
                .resolve("kernel")
                .resolve("src")
                .resolve("main")
                .resolve("java")
                .resolve("com")
                .resolve("npdev")
                .resolve("kernel")
                .resolve("dbschema")
                .toAbsolutePath()
                .normalize();
    }
}
