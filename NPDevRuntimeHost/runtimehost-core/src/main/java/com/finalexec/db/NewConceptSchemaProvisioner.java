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
 * concept's own table -- every field that call path does not read (renames, FKs, indexes, uniques,
 * destructive-recreate posture) is left at its empty/false default. Public, like
 * {@link DesiredSchemaFactory}, the sibling pure-projection utility in this package -- the raw DDL
 * executor underneath ({@link MissingTableCreationPass}) stays package-private.
 *
 * <p>Deliberately narrow scope: a concept with any bond/reference field is refused, not silently
 * skipped or mishandled, because resolving its FK column's type and cardinality needs
 * {@code BondModelSupport} (generator-only, a build-time module RuntimeHost does not depend on) --
 * REG-244's own still-open "bond/junction-table edge case", left for a later phase. A satellite
 * concept has no table of its own; a temporal concept needs a history table neither this pass nor
 * {@link MissingTableCreationPass} knows how to create. Every refusal is named in
 * {@link Result#failed()} with its reason, never dropped silently.
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
            String outOfScopeReason = outOfScopeReason(concept);
            if (outOfScopeReason != null) {
                failed.put(concept.getName(), outOfScopeReason);
                continue;
            }
            try {
                MissingTableCreationPass.createMissingBusinessTables(dataSource, manifestFor(concept));
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
     * table for. Public for that cross-package reuse; behavior unchanged from Phase 4B.
     */
    public static String outOfScopeReason(CompiledConcept concept) {
        if (concept.getSatelliteOf() != null) {
            return "satellite concept: stored on another concept's table, has no table of its own";
        }
        if (concept.isTemporal()) {
            return "temporal concepts are not yet supported by live schema provisioning";
        }
        for (CompiledField field : concept.getFields()) {
            if (field.getReferenceTarget() != null) {
                return "concept has a bond/reference field ('" + field.getName()
                        + "'); live provisioning of bonded concepts is not yet supported (REG-244)";
            }
        }
        return null;
    }

    /**
     * Mirrors {@code SchemaRealizationEmitter.columnTypes}'s non-bond branch exactly (same platform
     * columns, same synthetic "id" fallback), using only types already on RuntimeHost's runtime
     * classpath ({@code SqlTypeSupport}/{@code SqlIdentifierSupport}, both in the shared {@code dsl}
     * module) -- never the generator's {@code BondModelSupport}, which this concept is guaranteed not
     * to need by {@link #outOfScopeReason}'s check above.
     */
    private static SchemaLifecycleExecutor.SchemaManifest manifestFor(CompiledConcept concept) {
        String table = SqlIdentifierSupport.tableName(concept);
        List<String> columnNames = new ArrayList<>();
        Map<String, String> columnTypes = new LinkedHashMap<>();
        List<String> requiredColumns = new ArrayList<>();
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

        // Every field below this point is unused by MissingTableCreationPass.createMissingBusinessTables
        // -> DesiredSchemaFactory.fromManifest -> businessColumnDefs for a brand-new table (confirmed by
        // reading both): no renames, no FK/index/unique constraints, no destructive-recreate posture.
        // Left at empty/false/null defaults rather than invented values.
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
                Map.of());
    }
}
