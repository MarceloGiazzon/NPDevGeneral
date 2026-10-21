package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * LIVE-1: the REST-metadata view of the fields a live {@code /model-reload} added to a concept the
 * generated CRUD controller was baked for. The controller's {@code ConceptMetadata} is built once
 * from the model at generation time and drives response projection, sort and where validation; this
 * supplies the entries for fields it does not know yet, using the same label / type / sortable /
 * filterable / widget rules {@link LiveConceptUiManifestSupport} applies to a live-provisioned
 * concept (so the REST view and the served UI manifest agree on a new field).
 */
public final class LiveFieldMetadata {

    private LiveFieldMetadata() {
    }

    public record Meta(String name, String label, String columnName, String type, boolean required,
            boolean sortable, boolean filterable, String widget, List<String> enumValues) {
    }

    /** Every field of the live concept whose name is not in {@code knownNames}, in declared order. */
    public static List<Meta> addedFields(CompiledConcept live, Collection<String> knownNames) {
        List<Meta> out = new ArrayList<>();
        if (live == null) {
            return out;
        }
        for (CompiledField field : live.getFieldsInDeclaredOrder()) {
            if (field == null || field.getName() == null || knownNames.contains(field.getName())) {
                continue;
            }
            out.add(new Meta(
                    field.getName(),
                    LiveConceptUiManifestSupport.fieldLabel(field),
                    SqlIdentifierSupport.columnName(field),
                    LiveConceptUiManifestSupport.manifestType(field),
                    field.isRequired(),
                    LiveConceptUiManifestSupport.isSortable(field),
                    LiveConceptUiManifestSupport.isFilterable(field),
                    LiveConceptUiManifestSupport.widget(field, false),
                    List.copyOf(field.getEnumValues() == null ? List.of() : field.getEnumValues())));
        }
        return out;
    }
}
