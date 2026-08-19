package com.npdev.generator.dbconfig;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;

import java.util.Locale;
import java.util.Set;

/**
 * REG-64/F10 (docs/FINAL_OPEN_ITEMS_PLAN.md): extracted from SchemaRealizationEmitter's own
 * validateNoReservedColumnCollision so EntityEmitter can run the SAME check before Java field
 * emission, not only before SQL DDL emission -- one reserved-name list, not two.
 *
 * <p>"version"/"row_version"/"tenant_id" are platform-reserved business-table columns: every
 * generated entity gets them implicitly (optimistic concurrency; LNCH-16 CAS updates through
 * ConceptGateway; tenant isolation), regardless of what the model declares. Confirmed live (Claude
 * Support Desk, REG-64): without this guard at the Java-entity layer, a model field whose column
 * name collides produces a duplicate-field {@code javac} error -- the schema-layer guard existed
 * and was correct, but ran downstream of Java compilation, so its actionable message never had a
 * chance to show.
 */
public final class ReservedColumnNames {

    // "deleted_at" reserved unconditionally (R5.4), like the other three, even though the column is
    // only actually emitted for a concept declaring softDelete: true -- so a model author cannot pick
    // a field name that collides the moment that concept later turns softDelete on.
    public static final Set<String> RESERVED_BUSINESS_COLUMN_NAMES = Set.of("version", "row_version", "tenant_id", "deleted_at");

    private ReservedColumnNames() {
    }

    public static void validateNoCollision(CompiledConcept concept) {
        for (CompiledField field : concept.getFields()) {
            String column = SqlIdentifierSupport.columnName(field);
            if (RESERVED_BUSINESS_COLUMN_NAMES.contains(column.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(
                        "Concept " + concept.getName() + " has a field '" + field.getName()
                                + "' whose column name '" + column + "' collides with a platform-reserved "
                                + "business-table column (every generated table implicitly gets 'version' "
                                + "for optimistic concurrency, 'row_version' for LNCH-16 CAS updates through "
                                + "ConceptGateway, 'tenant_id' for tenant isolation, and -- for a concept "
                                + "declaring softDelete: true -- 'deleted_at' for R5.4 soft delete). "
                                + "Rename this field in the model to something else (e.g. '"
                                + field.getName() + "Ref').");
            }
        }
    }
}
