package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.SchemaAst;
import com.npdev.dsl.v1.ast.SequenceAst;
import com.npdev.dsl.v1.expr.SequenceNumberFormat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * R5.3: structural checks for the optional top-level {@code sequences} declaration -- name
 * shape/global uniqueness (the same closed loop {@link WebhookValidation} runs for {@code
 * source}: {@code sequences[].name} is deliberately never pack-qualified, see {@link
 * SequenceAst}'s own javadoc, so a genuine cross-pack collision must be caught here rather than
 * relying on qualification to make it structurally impossible), a well-formed {@code format}
 * ({@link SequenceNumberFormat#validate}), and a recognized {@code scope} -- plus a scan of every
 * concept field's value-behavior expressions for {@code nextNumber('name')} calls: a
 * {@code defaultExpression} referencing an undeclared sequence name is a compile-time error naming
 * it (the same discipline {@code FlowValidation} applies to {@code awaitEvent}/{@code emitEvent}),
 * and {@code nextNumber()} is refused entirely inside {@code derivedExpression} -- a derived value
 * is recomputed on every save, so calling it there would silently allocate a fresh number on every
 * unrelated update instead of once at create time.
 */
final class SequenceValidation {

    /** Same shape discipline as {@code WebhookValidation}'s {@code SOURCE_PATTERN}: a sequence
     *  name is embedded literally inside {@code nextNumber('name')}, so it must be safe there. */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Set<String> VALID_SCOPES = Set.of("global", "tenant");
    private static final Pattern NEXT_NUMBER_CALL = Pattern.compile("nextNumber\\(\\s*['\"]([^'\"]*)['\"]\\s*\\)");

    private SequenceValidation() {
    }

    static void validateSequences(ModelAst modelAst, List<String> errors) {
        Set<String> namesSeen = new HashSet<>();
        Set<String> declaredNames = new HashSet<>();
        for (SequenceAst sequence : modelAst.getSequences()) {
            if (hasText(sequence.name())) {
                declaredNames.add(normalize(sequence.name()));
            }
        }

        for (SequenceAst sequence : modelAst.getSequences()) {
            String here = "Sequence " + (hasText(sequence.name()) ? sequence.name() : "<unnamed>");
            if (!hasText(sequence.name())) {
                errors.add("Sequence: name is required -- suggestedFix: add a 'name' to every entry in sequences[]");
            } else {
                if (!NAME_PATTERN.matcher(sequence.name()).matches()) {
                    errors.add(here + ": name must match ^[A-Za-z_][A-Za-z0-9_]*$ (it is referenced literally by "
                            + "nextNumber('name')) -- suggestedFix: rename the sequence to start with a letter or "
                            + "underscore and use only letters, digits, and underscores");
                }
                if (!namesSeen.add(normalize(sequence.name()))) {
                    errors.add(here + ": duplicate sequence name -- suggestedFix: give each entry in sequences[] "
                            + "a distinct name");
                }
            }
            if (!hasText(sequence.format())) {
                errors.add(here + ": format is required -- suggestedFix: add a format such as "
                        + "\"INV-{year}-{seq:4}\" with exactly one {seq}/{seq:N} token");
            } else {
                try {
                    SequenceNumberFormat.validate(sequence.format());
                } catch (SequenceNumberFormat.FormatException malformed) {
                    errors.add(here + ": format is invalid: " + malformed.getMessage()
                            + " -- suggestedFix: use only {seq}/{seq:N}/{year}/{yy}/{month}/{day} tokens, with "
                            + "exactly one {seq}/{seq:N}");
                }
            }
            if (hasText(sequence.scope()) && !VALID_SCOPES.contains(normalize(sequence.scope()))) {
                errors.add(here + ": scope must be 'global' or 'tenant', got '" + sequence.scope()
                        + "' -- suggestedFix: set scope to 'global' or 'tenant', or omit it for 'global'");
            }
        }

        for (ConceptAst concept : modelAst.getConcepts()) {
            for (FieldAst field : concept.getFields()) {
                SchemaAst schema = field.getSchema();
                if (schema == null) {
                    continue;
                }
                validateNextNumberReferences(concept.getName(), field.getName(), "defaultExpression",
                        schema.getDefaultExpression(), declaredNames, true, errors);
                validateNextNumberReferences(concept.getName(), field.getName(), "derivedExpression",
                        schema.getDerivedExpression(), declaredNames, false, errors);
            }
        }
    }

    private static void validateNextNumberReferences(
            String conceptName,
            String fieldName,
            String kind,
            String expression,
            Set<String> declaredNames,
            boolean allowedHere,
            List<String> errors
    ) {
        if (!hasText(expression)) {
            return;
        }
        Matcher matcher = NEXT_NUMBER_CALL.matcher(expression);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String sequenceName = matcher.group(1);
            if (!declaredNames.contains(normalize(sequenceName))) {
                errors.add("Entity " + conceptName + " field " + fieldName + ": " + kind
                        + " calls nextNumber('" + sequenceName + "') but no sequence with that name is declared "
                        + "-- suggestedFix: add '" + sequenceName + "' to the model's sequences[], or fix the "
                        + "typo in nextNumber('" + sequenceName + "')");
            }
        }
        if (found && !allowedHere) {
            errors.add("Entity " + conceptName + " field " + fieldName + ": " + kind
                    + " calls nextNumber(), which is only allowed in defaultExpression -- a derivedExpression is "
                    + "recomputed on every save and would allocate a new number on every unrelated update "
                    + "-- suggestedFix: move nextNumber(...) to this field's defaultExpression instead");
        }
    }
}
