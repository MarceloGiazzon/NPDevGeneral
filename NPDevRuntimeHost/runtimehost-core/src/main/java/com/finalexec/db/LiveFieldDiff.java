package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.dsl.v1.compiled.SqlTypeSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LIVE-1: the field-level difference between the model a running app serves and a candidate model a
 * {@code /model-reload} wants to swap in, restricted to the ONE change this platform can apply
 * without a rebuild -- a new, optional, plain scalar field on a concept that already exists.
 *
 * <p>Everything else that changes a concept's physical shape is a REFUSAL, never a silent swap: a
 * field that disappeared (a removal, or the old half of a rename), a field whose type (and therefore
 * column type) changed, and a new field that is required, unique, an id, a reference/bond, a JSON-typed
 * (object/array/file) field, or a column name that collides with an existing one. Those stay on the
 * rebuild path, where the schema lifecycle's acknowledgment tokens own them. A concept that is a
 * satellite (stored on another concept's table) or temporal (has a history table) is refused for any
 * new field for the same reason {@link NewConceptSchemaProvisioner} refuses it as a new concept.
 *
 * <p>Pure projection of two {@link CompiledModel}s -- no database access -- so
 * {@code ModelReloadPreflight} can run it before anything is touched and
 * {@link ExistingConceptColumnProvisioner} can run it again to learn exactly which columns to add.
 */
public final class LiveFieldDiff {

    private LiveFieldDiff() {
    }

    /** One column to add: {@code table}/{@code column} are the raw (unquoted) physical names and
     *  {@code sqlType} the engine-neutral type {@link SqlTypeSupport} derives for the field. */
    public record AddedField(String concept, String field, String table, String column, String sqlType) {
        public String label() {
            return concept + "." + field;
        }
    }

    public record Result(List<AddedField> added, List<String> refusals) {
        public boolean refused() {
            return !refusals.isEmpty();
        }
    }

    private static final Set<String> JSON_TYPES = Set.of("object", "array", "file");

    public static Result compute(CompiledModel live, CompiledModel candidate) {
        List<AddedField> added = new ArrayList<>();
        List<String> refusals = new ArrayList<>();
        if (live == null || candidate == null) {
            return new Result(List.of(), List.of());
        }
        for (CompiledConcept liveConcept : live.getConcepts()) {
            CompiledConcept next = candidate.findConcept(liveConcept.getName()).orElse(null);
            if (next == null) {
                continue; // a removed concept is ModelReloadPreflight's own RELOAD_REMOVES_CONCEPTS refusal
            }
            diffConcept(liveConcept, next, added, refusals);
        }
        return new Result(List.copyOf(added), List.copyOf(refusals));
    }

    private static void diffConcept(CompiledConcept live, CompiledConcept next,
            List<AddedField> added, List<String> refusals) {
        Map<String, CompiledField> liveByName = byName(live);
        Map<String, CompiledField> nextByName = byName(next);
        String conceptName = live.getName();

        for (CompiledField liveField : live.getFields()) {
            CompiledField nextField = nextByName.get(liveField.getName());
            if (nextField == null) {
                refusals.add(conceptName + "." + liveField.getName()
                        + ": the field was removed or renamed -- that is a destructive schema change and needs a rebuild");
                continue;
            }
            String liveType = SqlTypeSupport.sqlType(liveField);
            String nextType = SqlTypeSupport.sqlType(nextField);
            if (!normalize(liveField.getDslType()).equals(normalize(nextField.getDslType()))
                    || !liveType.equalsIgnoreCase(nextType)) {
                refusals.add(conceptName + "." + liveField.getName() + ": the field's type changed from "
                        + liveField.getDslType() + " (" + liveType + ") to " + nextField.getDslType()
                        + " (" + nextType + ") -- a retype or length change needs a rebuild");
            }
        }

        Set<String> usedColumns = new java.util.HashSet<>();
        for (CompiledField liveField : live.getFields()) {
            usedColumns.add(SqlIdentifierSupport.columnName(liveField));
        }
        boolean structural = next.getSatelliteOf() != null || next.isTemporal();
        for (CompiledField nextField : next.getFields()) {
            if (liveByName.containsKey(nextField.getName())) {
                continue;
            }
            String label = conceptName + "." + nextField.getName();
            String reason = ineligibleReason(next, nextField, structural);
            String column = SqlIdentifierSupport.columnName(nextField);
            if (reason == null && (column.isEmpty() || !column.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))) {
                reason = "its name does not map to a plain SQL column name";
            }
            if (reason == null && !usedColumns.add(column)) {
                reason = "its column name '" + column + "' collides with an existing column of the concept";
            }
            if (reason != null) {
                refusals.add(label + ": a new field " + reason + " -- only an optional plain scalar field "
                        + "can be added to a running app; anything else needs a rebuild");
                continue;
            }
            added.add(new AddedField(conceptName, nextField.getName(), SqlIdentifierSupport.tableName(next),
                    column, SqlTypeSupport.sqlType(nextField)));
        }
    }

    private static String ineligibleReason(CompiledConcept concept, CompiledField field, boolean structural) {
        if (structural) {
            return concept.getSatelliteOf() != null
                    ? "on a satellite concept (stored on another concept's table)"
                    : "on a temporal concept (it has a history table too)";
        }
        if (field.isId()) {
            return "is an id field";
        }
        if (field.isRequired()) {
            return "is required (make it optional, or rebuild)";
        }
        if (field.isUnique()) {
            return "is unique (needs a unique constraint)";
        }
        if (field.getReferenceTarget() != null || "reference".equals(normalize(field.getDslType()))) {
            return "is a reference/bond (needs a foreign key)";
        }
        if (JSON_TYPES.contains(normalize(field.getDslType()))) {
            return "is a JSON-typed (" + field.getDslType() + ") field";
        }
        if (field.getRenamedFrom() != null && !field.getRenamedFrom().isBlank()) {
            return "declares renamedFrom (a rename)";
        }
        return null;
    }

    private static Map<String, CompiledField> byName(CompiledConcept concept) {
        Map<String, CompiledField> out = new LinkedHashMap<>();
        for (CompiledField field : concept.getFields()) {
            out.put(field.getName(), field);
        }
        return out;
    }

    private static String normalize(String dslType) {
        return dslType == null ? "" : dslType.trim().toLowerCase(Locale.ROOT);
    }
}
