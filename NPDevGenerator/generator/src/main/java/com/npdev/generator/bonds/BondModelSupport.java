package com.npdev.generator.bonds;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.dsl.v1.compiled.SqlTypeSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class BondModelSupport {
    private BondModelSupport() {
    }

    public enum Cardinality {
        MANY_TO_ONE,
        ONE_TO_ONE,
        MANY_TO_MANY
    }

    public static final class Bond {
        private final CompiledConcept sourceConcept;
        private final CompiledField sourceField;
        private final CompiledConcept targetConcept;
        private final CompiledField anchorField;
        private final Cardinality cardinality;
        private final String via;
        private final String onDelete;

        private Bond(
                CompiledConcept sourceConcept,
                CompiledField sourceField,
                CompiledConcept targetConcept,
                CompiledField anchorField,
                Cardinality cardinality,
                String via,
                String onDelete
        ) {
            this.sourceConcept = sourceConcept;
            this.sourceField = sourceField;
            this.targetConcept = targetConcept;
            this.anchorField = anchorField;
            this.cardinality = cardinality;
            this.via = via;
            this.onDelete = onDelete;
        }

        public CompiledConcept sourceConcept() {
            return sourceConcept;
        }

        public CompiledField sourceField() {
            return sourceField;
        }

        public CompiledConcept targetConcept() {
            return targetConcept;
        }

        public CompiledField anchorField() {
            return anchorField;
        }

        public Cardinality cardinality() {
            return cardinality;
        }

        public String via() {
            return via;
        }

        public String onDelete() {
            return onDelete;
        }

        public String sourceTable() {
            return SqlIdentifierSupport.tableName(sourceConcept);
        }

        public String targetTable() {
            return SqlIdentifierSupport.tableName(targetConcept);
        }

        public String sourceColumn() {
            return SqlIdentifierSupport.columnName(sourceField);
        }

        public String anchorColumn() {
            return SqlIdentifierSupport.columnName(anchorField);
        }

        public String effectiveJavaType() {
            return anchorField.getJavaType();
        }

        public String effectiveSqlType() {
            return mapSqlType(anchorField);
        }

        public String onDeleteSql() {
            return BondModelSupport.onDeleteSql(onDelete);
        }

        public String onUpdateSqlClause() {
            return anchorField != null && !anchorField.isId() ? " ON UPDATE CASCADE" : "";
        }

        /**
         * Junction table + column naming. MUST stay byte-identical to the runtime mirror in
         * {@code GeneratedCrudRuntimeSupport.requireBondRuntimeShape} (NPDevKernel), otherwise the
         * generated migration and the runtime SQL disagree and N:M CRUD hits a missing table.
         * The generator side is pinned by {@code FlywayEmitterBondsTest}; keep both in sync.
         */
        public String junctionTable() {
            return SqlIdentifierSupport.junctionTableName(sourceConcept, sourceField);
        }
    }

    public static Map<String, CompiledConcept> conceptsByName(CompiledModel model) {
        Map<String, CompiledConcept> conceptsByName = new LinkedHashMap<>();
        if (model == null) {
            return conceptsByName;
        }
        for (CompiledConcept concept : model.getConcepts()) {
            if (concept != null && concept.getName() != null) {
                conceptsByName.put(normalize(concept.getName()), concept);
            }
        }
        return conceptsByName;
    }

    public static List<Bond> allBonds(CompiledModel model) {
        Map<String, CompiledConcept> conceptsByName = conceptsByName(model);
        List<Bond> bonds = new ArrayList<>();
        if (model == null) {
            return bonds;
        }
        for (CompiledConcept concept : model.getConcepts()) {
            if (concept == null) {
                continue;
            }
            for (CompiledField field : concept.getFields()) {
                resolveBond(concept, field, conceptsByName).ifPresent(bonds::add);
            }
        }
        return List.copyOf(bonds);
    }

    public static Optional<Bond> resolveBond(
            CompiledConcept sourceConcept,
            CompiledField sourceField,
            Map<String, CompiledConcept> conceptsByName
    ) {
        if (sourceConcept == null || sourceField == null || sourceField.isId() || !isDeclaredReference(sourceField)) {
            return Optional.empty();
        }
        String targetName = targetName(sourceField);
        if (targetName == null || targetName.isBlank()) {
            return Optional.empty();
        }
        CompiledConcept target = conceptsByName.get(normalize(targetName));
        if (target == null) {
            throw new IllegalStateException("Declared bond " + sourceConcept.getName() + "."
                    + sourceField.getName() + " targets unknown concept: " + targetName);
        }
        CompiledField anchor = resolveAnchorField(sourceField, target);
        if (anchor == null) {
            String via = sourceField.getReferenceSemantics() == null ? null : sourceField.getReferenceSemantics().getVia();
            throw new IllegalStateException("Declared bond " + sourceConcept.getName() + "."
                    + sourceField.getName() + " has no resolvable target anchor"
                    + (via == null || via.isBlank() ? "" : ": " + via));
        }
        CompiledReferenceSemantics semantics = sourceField.getReferenceSemantics();
        String via = semantics == null ? null : semantics.getVia();
        if (!isValidBondAnchor(anchor)) {
            String viaLabel = via == null || via.isBlank() ? anchor.getName() : via;
            throw new IllegalStateException("Bond resolution error: " + sourceConcept.getName() + "."
                    + sourceField.getName() + " via '" + viaLabel + "' targets " + target.getName() + "."
                    + anchor.getName() + ", but the target field is not a valid bond anchor. "
                    + "A via anchor must be the id field or a unique field marked connectable:\"anchor\".");
        }
        boolean multiple = semantics != null && semantics.isMultiple();
        Cardinality cardinality = multiple
                ? Cardinality.MANY_TO_MANY
                : (sourceField.isUnique() ? Cardinality.ONE_TO_ONE : Cardinality.MANY_TO_ONE);
        String onDelete = semantics == null ? null : semantics.getOnDelete();
        return Optional.of(new Bond(sourceConcept, sourceField, target, anchor, cardinality, via, onDelete));
    }

    private static boolean isValidBondAnchor(CompiledField anchor) {
        if (anchor == null) {
            return false;
        }
        if (anchor.isId()) {
            return true;
        }
        return anchor.isUnique() && "anchor".equalsIgnoreCase(anchor.getConnectable());
    }

    public static boolean isDeclaredReference(CompiledField field) {
        if (field == null) {
            return false;
        }
        CompiledReferenceSemantics semantics = field.getReferenceSemantics();
        if (semantics != null && semantics.getTarget() != null && !semantics.getTarget().isBlank()) {
            return true;
        }
        return field.getReferenceTarget() != null && !field.getReferenceTarget().isBlank();
    }

    public static String targetName(CompiledField field) {
        if (field == null) {
            return "";
        }
        CompiledReferenceSemantics semantics = field.getReferenceSemantics();
        if (semantics != null && semantics.getTarget() != null && !semantics.getTarget().isBlank()) {
            return semantics.getTarget();
        }
        return field.getReferenceTarget();
    }

    public static CompiledField resolveAnchorField(CompiledField sourceField, CompiledConcept target) {
        if (sourceField == null || target == null) {
            return null;
        }
        CompiledReferenceSemantics semantics = sourceField.getReferenceSemantics();
        String via = semantics == null ? null : semantics.getVia();
        if (via == null || via.isBlank()) {
            return idFieldOrNull(target);
        }
        return fieldByName(target, via);
    }

    public static CompiledField idField(CompiledConcept concept) {
        CompiledField found = idFieldOrNull(concept);
        if (found == null) {
            throw new IllegalStateException("Concept " + concept.getName() + " must have exactly one id field.");
        }
        return found;
    }

    public static CompiledField idFieldOrNull(CompiledConcept concept) {
        CompiledField found = null;
        if (concept == null) {
            return null;
        }
        for (CompiledField field : concept.getFields()) {
            if (field == null || !field.isId()) {
                continue;
            }
            if (found != null) {
                throw new IllegalStateException("Concept " + concept.getName() + " must have exactly one id field.");
            }
            found = field;
        }
        return found;
    }

    public static CompiledField fieldByName(CompiledConcept concept, String name) {
        if (concept == null || name == null) {
            return null;
        }
        for (CompiledField field : concept.getFields()) {
            if (field != null && name.equalsIgnoreCase(field.getName())) {
                return field;
            }
        }
        return null;
    }

    public static String safeTable(CompiledConcept concept) {
        return SqlIdentifierSupport.tableName(concept);
    }

    public static String toSnake(String value) {
        return SqlIdentifierSupport.toSnake(value);
    }

    public static String mapSqlType(CompiledField field) {
        return SqlTypeSupport.sqlType(field);
    }

    public static String onDeleteSql(String policy) {
        String normalized = policy == null ? "" : policy.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "cascade" -> "CASCADE";
            case "nullify" -> "SET NULL";
            default -> "RESTRICT";
        };
    }

    public static String truncateIdentifier(String value) {
        return SqlIdentifierSupport.safeSqlIdentifier(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
