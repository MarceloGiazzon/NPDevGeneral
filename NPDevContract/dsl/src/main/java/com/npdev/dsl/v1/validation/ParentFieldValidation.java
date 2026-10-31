package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ConceptAccessAst;
import com.npdev.dsl.v1.ast.FieldAst;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SEC-16 (parent-scope half): {@code parentField('<referenceField>', '<targetField>')} in a concept's
 * {@code access.write} reads one field of the row a reference field points at, so a child row can be
 * written only by whoever may write under its PARENT -- {@code participantId.coordinator == $user.id}
 * spelled as {@code parentField('participantId', 'coordinator') == $user.id}.
 *
 * <p>The runtime (ConfiguredConceptGatewaySemanticPolicy) denies a write whose parent cannot be found, so a
 * mistake here would silently lock every writer out; this refuses the mistakes when the model is validated:
 * the arguments must be two quoted names, the first a reference field of this concept, the second a field
 * of the concept it targets. It is a write-rule function only: in {@code access.read} it could not be
 * pushed into the per-caller SQL scope, and in a field-level rule there is no row-level parent to consult.
 */
final class ParentFieldValidation {

    private static final String CALL = "parentField";
    private static final Pattern ANY_CALL = Pattern.compile("\\bparentField\\s*\\(");
    private static final Pattern WELL_FORMED = Pattern.compile(
            "\\bparentField\\s*\\(\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*\\)");

    private ParentFieldValidation() {
    }

    /**
     * @param effectiveFieldsOf the effective (inheritance-resolved) fields of a concept by lower-cased name,
     *                          or null when no such concept exists
     */
    static void validate(
            String entityName,
            ConceptAccessAst access,
            List<FieldAst> ownFields,
            Function<String, List<FieldAst>> effectiveFieldsOf,
            List<String> errors
    ) {
        if (access == null) {
            return;
        }
        if (calls(access.getRead())) {
            errors.add("Entity " + entityName + " access.read: " + CALL + "(...) is only supported in access.write "
                    + "-- a read rule must stay inside the SQL-translatable subset so it can scope every "
                    + "query and aggregate per caller -- suggestedFix: keep the parent's owner on this concept");
        }
        String write = access.getWrite();
        if (!calls(write)) {
            return;
        }
        Matcher any = ANY_CALL.matcher(write);
        int calls = 0;
        while (any.find()) {
            calls++;
        }
        Matcher good = WELL_FORMED.matcher(write);
        int wellFormed = 0;
        while (good.find()) {
            wellFormed++;
            checkCall(entityName, good.group(1), good.group(2), ownFields, effectiveFieldsOf, errors);
        }
        if (wellFormed != calls) {
            errors.add("Entity " + entityName + " access.write: " + CALL + " takes exactly two quoted field names, "
                    + CALL + "('referenceField', 'targetField') -- suggestedFix: e.g. "
                    + CALL + "('participantId', 'coordinator') == $user.id");
        }
    }

    /** A field-level rule has no parent row to consult. */
    static void refuseInFieldRule(String fieldLabel, String read, String write, List<String> errors) {
        if (calls(read) || calls(write)) {
            errors.add("Entity " + fieldLabel + " access: " + CALL + "(...) is only supported in a concept's "
                    + "access.write, not in a field-level rule -- suggestedFix: move the rule to the concept's access.write");
        }
    }

    private static void checkCall(
            String entityName,
            String referenceField,
            String targetField,
            List<FieldAst> ownFields,
            Function<String, List<FieldAst>> effectiveFieldsOf,
            List<String> errors
    ) {
        String where = "Entity " + entityName + " access.write: " + CALL + "('" + referenceField + "', '"
                + targetField + "')";
        FieldAst reference = find(ownFields, referenceField);
        if (reference == null) {
            errors.add(where + " names unknown field '" + referenceField + "' on " + entityName
                    + " -- suggestedFix: use the name of a reference field declared on " + entityName);
            return;
        }
        String target = reference.getReferenceTarget();
        if (!"reference".equalsIgnoreCase(String.valueOf(reference.getType())) || target == null || target.isBlank()) {
            errors.add(where + ": '" + referenceField + "' is not a reference field, so it has no parent -- "
                    + "suggestedFix: name a field declared with type reference");
            return;
        }
        List<FieldAst> targetFields = effectiveFieldsOf.apply(target.trim().toLowerCase(Locale.ROOT));
        if (targetFields == null) {
            errors.add(where + ": reference target " + target + " is not a declared concept "
                    + "-- suggestedFix: point the reference at an existing concept");
            return;
        }
        if (find(targetFields, targetField) == null) {
            errors.add(where + ": " + target + " has no field '" + targetField + "' -- suggestedFix: name a field declared on "
                    + target);
        }
    }

    private static FieldAst find(List<FieldAst> fields, String name) {
        for (FieldAst field : fields) {
            if (field.getName() != null && field.getName().equalsIgnoreCase(name)) {
                return field;
            }
        }
        return null;
    }

    private static boolean calls(String expression) {
        return expression != null && ANY_CALL.matcher(expression).find();
    }
}
