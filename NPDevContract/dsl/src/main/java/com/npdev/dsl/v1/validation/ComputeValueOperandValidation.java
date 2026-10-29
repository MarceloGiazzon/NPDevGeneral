package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.ProcedureStepAst;
import com.npdev.dsl.v1.expr.DateFunctions;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * REG-313: the kernel's {@code computeValue} fails a step whose operand is of the wrong kind with
 * {@code COMPUTE_VALUE_INVALID_OPERAND} -- {@code add}/{@code subtract} need numbers, {@code addDays}
 * needs a date and a whole number of days, {@code daysBetween} needs two dates. This refuses, at model
 * time, the operands that are PROVABLY of the wrong kind, so "accepted implies enforced" holds for them:
 * <ul>
 *   <li>a literal of the wrong kind (a boolean, a map/list, a string that is not a number / not an ISO
 *       date, a fractional day count -- including {@code "$$x"}, the escape for a literal string starting
 *       with {@code $});</li>
 *   <li>{@code $step.field} where {@code step} is the {@code target} of a {@code readConcept} step and
 *       {@code field} is declared on that concept with a type that can never hold that kind of value.</li>
 * </ul>
 * Deliberately conservative -- an operand it cannot type is left to the runtime: {@code $input.*}, a
 * string field (may hold digits or a date), an undeclared field (another validator's concern), and a
 * target bound more than once to different concepts. The message carries the runtime's own code so the
 * two greps meet; {@code ValidationDiagnosticNormalizer} lifts it into the structured code field.
 */
final class ComputeValueOperandValidation {

    static final String CODE = "COMPUTE_VALUE_INVALID_OPERAND";

    private static final Set<String> COMPUTE_VALUE_STEP_TYPES =
            Set.of("computevalue", "compute_value", "compute", "arithmetic");
    private static final Set<String> READ_CONCEPT_STEP_TYPES = Set.of("readconcept", "read_concept");

    private static final Set<String> NEVER_NUMERIC_FIELD_TYPES =
            Set.of("date", "datetime", "boolean", "uuid", "object", "array", "file");
    private static final Set<String> NEVER_DATE_FIELD_TYPES =
            Set.of("int", "integer", "long", "decimal", "double", "float", "number", "short",
                    "boolean", "uuid", "object", "array", "file");

    /** The kind of value an operand slot takes. */
    private enum Expect {
        NUMBER("a number", "add/subtract"),
        WHOLE_NUMBER("a whole number of days", "addDays"),
        DATE("a date", "addDays/daysBetween");

        final String phrase;
        final String operators;

        Expect(String phrase, String operators) {
            this.phrase = phrase;
            this.operators = operators;
        }
    }

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
                Expect[] slots = slotsFor(step.operation());
                if (slots != null) {
                    String operator = step.operation().trim();
                    checkOperand(procedureName, stepPath, operator, "left", step.left(), slots[0], recordTargets, entitiesByLower, errors);
                    checkOperand(procedureName, stepPath, operator, "right", step.right(), slots[1], recordTargets, entitiesByLower, errors);
                }
            }
            check(procedureName, stepPath + ".then", step.thenSteps(), recordTargets, entitiesByLower, errors);
            check(procedureName, stepPath + ".else", step.elseSteps(), recordTargets, entitiesByLower, errors);
            check(procedureName, stepPath + ".steps", step.steps(), recordTargets, entitiesByLower, errors);
            index++;
        }
    }

    /** The left/right kinds an operator takes; null for {@code today} (no operands) and for an operator PackValidation already refuses. */
    private static Expect[] slotsFor(String operation) {
        String operator = operation == null ? "" : operation.trim().toLowerCase(Locale.ROOT);
        return switch (operator) {
            case "add", "subtract" -> new Expect[] {Expect.NUMBER, Expect.NUMBER};
            case "adddays" -> new Expect[] {Expect.DATE, Expect.WHOLE_NUMBER};
            case "daysbetween" -> new Expect[] {Expect.DATE, Expect.DATE};
            default -> null;
        };
    }

    private static void checkOperand(
            String procedureName,
            String stepPath,
            String operator,
            String side,
            Object operand,
            Expect expect,
            Map<String, String> recordTargets,
            Map<String, ConceptAst> entitiesByLower,
            List<String> errors
    ) {
        if (operand == null) {
            return; // "left/right is required" is reported by PackValidation
        }
        String here = "Procedure " + procedureName + " step " + stepPath + ": " + CODE + ": computeValue "
                + operator + " " + side;
        if (!(operand instanceof String text) || !text.startsWith("$") || text.startsWith("$$")) {
            if (!isLiteralOfKind(operand, expect)) {
                errors.add(here + " operand " + describe(operand) + " is not " + expect.phrase + "; computeValue "
                        + operator + " needs " + expect.phrase + " there -- suggestedFix: " + literalFix(expect));
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
        Set<String> impossible = expect == Expect.DATE ? NEVER_DATE_FIELD_TYPES : NEVER_NUMERIC_FIELD_TYPES;
        for (FieldAst field : conceptAst.getFields()) {
            String type = normalize(field.getType());
            if (normalize(field.getName()).equals(normalize(segments[1])) && impossible.contains(type)) {
                errors.add(here + " operand " + text + " is " + conceptAst.getName() + "." + field.getName()
                        + ", a " + type + " field, not " + expect.phrase + "; computeValue " + operator
                        + " fails at runtime on it -- suggestedFix: " + fieldFix(expect, type));
            }
        }
    }

    private static boolean isLiteralOfKind(Object operand, Expect expect) {
        return switch (expect) {
            case NUMBER -> isNumericLiteral(operand);
            case WHOLE_NUMBER -> isWholeNumberLiteral(operand);
            case DATE -> DateFunctions.isDate(operand);
        };
    }

    private static String literalFix(Expect expect) {
        return switch (expect) {
            case NUMBER -> "use a numeric literal or a reference to a numeric field";
            case WHOLE_NUMBER -> "use a whole-number literal (e.g. 84) or a reference to a numeric field";
            case DATE -> "use an ISO-8601 date literal (e.g. 2026-10-01) or a reference to a date field";
        };
    }

    private static String fieldFix(Expect expect, String fieldType) {
        return switch (expect) {
            case NUMBER -> "date".equals(fieldType) || "datetime".equals(fieldType)
                    ? "compute from a numeric field, or use operation addDays / daysBetween to do date arithmetic"
                    : "compute from a numeric field";
            case WHOLE_NUMBER -> "use a numeric field holding a whole number of days";
            case DATE -> "use a date or datetime field";
        };
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

    /** The runtime reads days only from a {@link Number} with no fractional part -- a numeric string is refused there too. */
    private static boolean isWholeNumberLiteral(Object operand) {
        if (!(operand instanceof Number number)) {
            return false;
        }
        try {
            return new BigDecimal(number.toString()).stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String describe(Object operand) {
        String text = String.valueOf(operand);
        return text.length() > 60 ? text.substring(0, 57) + "..." : text;
    }
}
