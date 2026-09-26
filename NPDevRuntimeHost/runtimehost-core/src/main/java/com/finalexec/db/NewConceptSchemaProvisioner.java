package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.dsl.v1.compiled.SqlTypeSupport;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REG-244 Phase 4B: creates the business table for a concept that appeared for the first time in a
 * {@code /model-reload} swap. Reuses {@link MissingTableCreationPass#createMissingBusinessTables},
 * the same idempotent ({@code IF NOT EXISTS}) primitive {@code npdev.trial.force-physical-schema}
 * already relies on, fed a {@link SchemaLifecycleExecutor.SchemaManifest} scoped to ONLY the new
 * concept's own table -- every field that call path does not read (renames, destructive-recreate
 * posture) is left at its empty/false default. Public, like {@link DesiredSchemaFactory}, the sibling
 * pure-projection utility in this package -- the raw DDL executor underneath
 * ({@link MissingTableCreationPass}) stays package-private.
 *
 * <p><b>Wave 6.3 (NPDEV_FEATURE_PLAN_2026-09-24.md) widened the scope</b> that REG-244 originally left
 * narrow: a reference field is now provisionable when its target concept ALREADY EXISTED before this
 * reload (checked against {@code oldModel}, the pre-swap model -- never the new one, so two brand-new
 * concepts referencing each other in the SAME reload still refuse, since neither's table is guaranteed
 * to exist before the other's FK would need it). The FK column itself needs no bond-specific
 * resolution RuntimeHost cannot do without the generator-only {@code BondModelSupport}: a reference
 * field's column name and SQL type come from the exact same {@link SqlIdentifierSupport#columnName}/
 * {@link SqlTypeSupport#sqlType} calls every other field already uses here (a reference field's own
 * dsl type, {@code "reference"}, is one of {@code SqlTypeSupport}'s recognized cases, mapping to
 * {@code UUID} -- confirmed by reading it, not assumed), and the referenced column is that target
 * concept's own id column, resolved the SAME "declared id field, else the synthetic 'id' fallback"
 * way this class already resolves its OWN concept's id. A reference to a concept that does NOT
 * already exist, a satellite concept (no table of its own), and a temporal concept (needs a history
 * table neither this pass nor {@link MissingTableCreationPass} knows how to create) are still
 * refused, every refusal named in {@link Result#failed()}, never dropped silently.
 */
public final class NewConceptSchemaProvisioner {

    private NewConceptSchemaProvisioner() {
    }

    /** {@code failed} maps a concept name to the reason it was not provisioned -- either an
     *  out-of-scope refusal (bond/satellite/temporal) or a real DDL failure. */
    public record Result(List<String> provisioned, Map<String, String> failed) {
    }

    public static Result provision(DataSource dataSource, CompiledModel oldModel, CompiledModel newModel) {
        Set<String> oldConceptNames = new LinkedHashSet<>();
        for (CompiledConcept concept : oldModel.getConcepts()) {
            oldConceptNames.add(concept.getName());
        }

        List<String> provisioned = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        for (CompiledConcept concept : newModel.getConcepts()) {
            if (oldConceptNames.contains(concept.getName())) {
                continue;
            }
            String outOfScopeReason = outOfScopeReason(concept, oldModel);
            if (outOfScopeReason != null) {
                failed.put(concept.getName(), outOfScopeReason);
                continue;
            }
            try {
                MissingTableCreationPass.createMissingBusinessTables(dataSource, manifestFor(concept, newModel));
                provisioned.add(concept.getName());
            } catch (IllegalStateException failure) {
                String detail = failure.getCause() == null ? failure.getMessage()
                        : failure.getMessage() + ": " + failure.getCause().getMessage();
                failed.put(concept.getName(), detail);
            }
        }
        return new Result(List.copyOf(provisioned), Map.copyOf(failed));
    }

    /**
     * REG-244 Phase 4C: also called by the generated {@code GeneratedConceptCrudController}'s live-
     * concept fallback ({@code business-concept-crud-controller.mustache}'s {@code resolveBinding})
     * to keep "has a table" (this class) and "is CRUD-reachable" (4C) from ever drifting apart -- a
     * concept only gets a live REST binding if it is exactly the shape this class would provision a
     * table for. That call site has no natural "pre-reload model" to check a reference field's target
     * against, so it keeps calling this single-arg form, which is UNCHANGED from Phase 4B: it refuses
     * every reference field, same as before Wave 6.3. Public for that cross-package reuse.
     */
    public static String outOfScopeReason(CompiledConcept concept) {
        return outOfScopeReason(concept, null);
    }

    /**
     * Wave 6.3: the widened check {@link #provision} actually uses, where {@code existingModel} is
     * the pre-reload model -- a reference field is in scope exactly when {@link
     * CompiledModel#findConcept} resolves its target THERE (never in the new model only: two
     * brand-new concepts referencing each other cannot be ordered safely, and this class provisions
     * one table per call with no notion of a dependency-sorted batch). {@code findConcept}, not a raw
     * name-set lookup, so this resolves a reference target exactly the same way
     * {@code CompiledMetadataCanonicalJson} already does -- whatever the target string's own
     * namespacing convention is, one resolver, not two. {@code existingModel == null} (the public
     * single-arg overload's delegation) means "nothing exists", so every reference field refuses --
     * byte-for-byte the pre-Wave-6.3 behavior.
     */
    static String outOfScopeReason(CompiledConcept concept, CompiledModel existingModel) {
        if (concept.getSatelliteOf() != null) {
            return "satellite concept: stored on another concept's table, has no table of its own";
        }
        if (concept.isTemporal()) {
            return "temporal concepts are not yet supported by live schema provisioning";
        }
        for (CompiledField field : concept.getFields()) {
            if (field.getReferenceTarget() == null) {
                continue;
            }
            boolean targetExists = existingModel != null
                    && existingModel.findConcept(field.getReferenceTarget()).isPresent();
            if (!targetExists) {
                return "concept has a reference field ('" + field.getName() + "') targeting '"
                        + field.getReferenceTarget() + "', which is not an already-existing concept -- "
                        + "live provisioning of a reference to another BRAND-NEW concept is not yet "
                        + "supported (Wave 6.3 only orders one new table per call)";
            }
        }
        return null;
    }

    /**
     * Mirrors {@code SchemaRealizationEmitter.columnTypes}'s non-bond branch exactly (same platform
     * columns, same synthetic "id" fallback), using only types already on RuntimeHost's runtime
     * classpath ({@code SqlTypeSupport}/{@code SqlIdentifierSupport}, both in the shared {@code dsl}
     * module) -- never the generator's {@code BondModelSupport}. {@code newModel} is only consulted
     * for a reference field's TARGET concept (to resolve its table/id-column for the FK) -- by the
     * time this runs, {@link #outOfScopeReason} has already proven every reference field here targets
     * something that existed before this reload, so {@code newModel.findConcept} always resolves.
     */
    private static SchemaLifecycleExecutor.SchemaManifest manifestFor(CompiledConcept concept, CompiledModel newModel) {
        String table = SqlIdentifierSupport.tableName(concept);
        List<String> columnNames = new ArrayList<>();
        Map<String, String> columnTypes = new LinkedHashMap<>();
        List<String> requiredColumns = new ArrayList<>();
        List<SchemaLifecycleExecutor.ForeignKeyDecl> foreignKeys = new ArrayList<>();
        List<SchemaLifecycleExecutor.IndexDecl> indexes = new ArrayList<>();
        boolean hasIdField = false;
        for (CompiledField field : concept.getFields()) {
            String columnName = SqlIdentifierSupport.columnName(field);
            columnNames.add(columnName);
            columnTypes.put(columnName, SqlTypeSupport.sqlType(field));
            if (field.isRequired()) {
                requiredColumns.add(columnName);
            }
            if (field.isId()) {
                hasIdField = true;
            }
            // Wave 6.3: outOfScopeReason already proved this resolves to an existing concept.
            if (field.getReferenceTarget() != null) {
                CompiledConcept targetConcept = newModel.findConcept(field.getReferenceTarget()).orElseThrow(() ->
                        new IllegalStateException("reference target '" + field.getReferenceTarget()
                                + "' did not resolve -- outOfScopeReason should have refused this concept first"));
                String targetTable = SqlIdentifierSupport.tableName(targetConcept);
                String targetIdColumn = idColumnOf(targetConcept);
                foreignKeys.add(new SchemaLifecycleExecutor.ForeignKeyDecl(
                        List.of(columnName), targetTable, List.of(targetIdColumn)));
                indexes.add(new SchemaLifecycleExecutor.IndexDecl(List.of(columnName), false));
            }
        }
        if (!hasIdField) {
            columnNames.add(0, "id");
            columnTypes.put("id", "UUID");
        }
        columnNames.add("version");
        columnTypes.put("version", "BIGINT");
        columnNames.add("row_version");
        columnTypes.put("row_version", "BIGINT");
        columnNames.add("tenant_id");
        columnTypes.put("tenant_id", "VARCHAR(120)");
        if (concept.isSoftDelete()) {
            columnNames.add("deleted_at");
            columnTypes.put("deleted_at", "TIMESTAMP WITH TIME ZONE");
        }

        // Every field below this point except the FK/index maps is unused by
        // MissingTableCreationPass.createMissingBusinessTables -> DesiredSchemaFactory.fromManifest ->
        // businessColumnDefs for a brand-new table (confirmed by reading both): no renames, no unique
        // constraints, no destructive-recreate posture. Left at empty/false/null defaults rather than
        // invented values. Uses the 24-arg constructor (SER-G8 shape) specifically because it is the
        // one that accepts the FK/index maps without also requiring the later
        // expression-defaults/uid maps this concept never needs.
        return new SchemaLifecycleExecutor.SchemaManifest(
                "", "", true, "",
                List.of(), List.of(table),
                Map.of(table, List.copyOf(columnNames)),
                Map.of(),
                Map.of(table, Map.copyOf(columnTypes)),
                Map.of(),
                Map.of(),
                false,
                "KeepExistingIfCompatible", "", null, null,
                Map.of(table, List.copyOf(requiredColumns)),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                "",
                foreignKeys.isEmpty() ? Map.of() : Map.of(table, List.copyOf(foreignKeys)),
                indexes.isEmpty() ? Map.of() : Map.of(table, List.copyOf(indexes)));
    }

    /** The declared id field's column name, or the same synthetic "id" fallback used for a concept
     *  provisioned with none -- shared by this concept's own id resolution above and a reference
     *  field's TARGET id resolution, so the two can never compute it differently. */
    private static String idColumnOf(CompiledConcept concept) {
        for (CompiledField field : concept.getFields()) {
            if (field.isId()) {
                return SqlIdentifierSupport.columnName(field);
            }
        }
        return "id";
    }
}
