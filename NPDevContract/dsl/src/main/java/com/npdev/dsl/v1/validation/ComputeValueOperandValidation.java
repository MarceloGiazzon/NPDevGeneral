package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.ProcedureStepAst;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * REG-313 (a): the kernel's {@code computeValue} (add/subtract) coerces both operands through
 * {@code BigDecimal} and fails the step with {@code COMPUTE_VALUE_INVALID_OPERAND} when one is not a
 * number -- e.g. {@code add(date, int)}. This refuses, at model time, the operands that are PROVABLY
 * non-numeric, so "accepted implies enforced" holds for them:
 * <ul>
 *   <li>a literal that is not a number (a boolean, a map/list, a string {@code BigDecimal} cannot parse --
 *       including {@code "$$x"}, the escape for a literal string starting with {@code $});</li>
 *   <li>{@code $step.field} where {@code step} is the {@code target} of a {@code readConcept} step
 *       and {@code field} is declared on that concept with a type that can never hold a number
 *       (date, datetime, boolean, uuid, object, array, file).</li>
 * </ul>
 * Deliberately conservative -- an operand it cannot type is left to the runtime: {@code $input.*}, a
 * string field (may hold digits), an undeclared field (another validator's concern), and a target
 * bound more than once to different concepts. The message carries the runtime's own code so the two
 * greps meet.
 */
final class ComputeValueOperandValidation {

    static final String CODE = "COMPUTE_VALUE_INVALID_OPERAND";

    private static final Set<String> COMPUTE_VALUE_STEP_TYPES =
            Set.of("computevalue", "compute_value", "compute", "arithmetic");
    private static final Set<String> READ_CONCEPT_STEP_TYPES = Set.of("readconcept", "read_concept");
    private static final Set<String> NEVER_NUMERIC_FIELD_TYPES =
            Set.of("date", "datetime", "boolean", "uuid", "object", "array", "file");

    private ComputeValueOperandValidation() {
    }

    static void validate(
            String procedureName,
            List<ProcedureStepAst> steps,
            Map<String, ConceptAst> entitiesByLower,
            List<String> errors
    ) {
        Map<String, String> recordTargets = new HashMap<>();
        collectRecordTargets(steps, recordTargets);
        check(procedureName, "procedures[" + procedureName + "].steps", steps, recordTargets, entitiesByLower, errors);
    }

    /** step target (lower-case) -> concept (lower-case); a target bound to two concepts maps to "" (ambiguous). */
    private static void collectRecordTargets(List<ProcedureStepAst> steps, Map<String, String> recordTargets) {
        for (ProcedureStepAst step : steps) {
            if (READ_CONCEPT_STEP_TYPES.contains(normalize(step.type()))
                    && step.target() != null && !step.target().isBlank()) {
                String concept = normalize(step.concept());
                recordTargets.merge(normalize(step.target()), concept, (a, b) -> a.equals(b) ? a : "");
            }
            collectRecordTargets(step.thenSteps(), recordTargets);
            collectRecordTargets(step.elseSteps(), recordTargets);
            collectRecordTargets(step.steps(), recordTargets);
        }
    }

    private static void check(
            String procedureName,
            String path,
            List<ProcedureStepAst> steps,
            Map<String, String> recordTargets,
            Map<String, ConceptAst> entitiesByLower,
            List<String> errors
    ) {
        int index = 0;
        for (ProcedureStepAst step : steps) {
            String stepPath = path + "[" + index + "]";
            if (COMPUTE_VALUE_STEP_TYPES.contains(normalize(step.type()))) {
                checkOperand(procedureName, stepPath, "left", step.left(), recordTargets, entitiesByLower, errors);
                checkOperand(procedureName, stepPath, "right", step.right(), recordTargets, entitiesByLower, errors);
            }
            check(procedureName, stepPath + ".then", step.thenSteps(), recordTargets, entitiesByLower, errors);
            check(procedureName, stepPath + ".else", step.elseSteps(), recordTargets, entitiesByLower, errors);
            check(procedureName, stepPath + ".steps", step.steps(), recordTargets, entitiesByLower, errors);
            index++;
        }
    }

    private static void checkOperand(
            String procedureName,
            String stepPath,
            String side,
            Object operand,
            Map<String, String> recordTargets,
            Map<String, ConceptAst> entitiesByLower,
            List<String> errors
    ) {
        if (operand == null) {
            return; // "left/right is required" is reported by PackValidation
        }
        String here = "Procedure " + procedureName + " step " + stepPath + ": " + CODE + ": computeValue " + side;
        if (!(operand instanceof String text) || !text.startsWith("$") || text.startsWith("$$")) {
            if (!isNumericLiteral(operand)) {
                errors.add(here + " operand " + describe(operand) + " is not a number; computeValue only does numeric"
                        + " add/subtract -- suggestedFix: use a numeric literal or a reference to a numeric field");
            }
            return;
        }
        String[] segments = text.substring(1).split("\\.");
        if (segments.length != 2) {
            return;
        }
        String concept = recordTargets.get(normalize(segments[0]));
        ConceptAst conceptAst = concept == null || concept.isEmpty() ? null : entitiesByLower.get(concept);
        if (conceptAst == null) {
            return;
        }
        for (FieldAst field : conceptAst.getFields()) {
            if (normalize(field.getName()).equals(normalize(segments[1]))
                    && NEVER_NUMERIC_FIELD_TYPES.contains(normalize(field.getType()))) {
                errors.add(here + " operand " + text + " is " + conceptAst.getName() + "." + field.getName()
                        + ", a " + normalize(field.getType()) + " field, not a number; computeValue only does numeric"
                        + " add/subtract and fails at runtime on it -- suggestedFix: compute from a numeric field,"
                        + " or move the date arithmetic out of computeValue");
            }
        }
    }

    private static boolean isNumericLiteral(Object operand) {
        if (operand instanceof Number) {
            return true;
        }
        if (operand instanceof String text) {
            try {
                new BigDecimal(text.trim());
                return true;
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return false;
    }

    private static String describe(Object operand) {
        String text = String.valueOf(operand);
        return text.length() > 60 ? text.substring(0, 57) + "..." : text;
    }
}
