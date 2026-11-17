package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.expr.ExprType;
import com.npdev.dsl.v1.expr.ExpressionTypeChecker;
import java.util.List;
import java.util.function.Function;

/**
 * FORM P3-T2: runs {@link ExpressionTypeChecker} in the contexts of the expression-language context table
 * that the validator reaches with a field list in hand (field {@code defaultExpression}/{@code
 * derivedExpression}, expression invariants). The checker reports definite mistakes only, so an unresolved
 * reference or an unknown function never produces an error here.
 */
final class TypedExpressionValidation {

    private TypedExpressionValidation() {
    }

    /** A field name to the expression type of its declared DSL type; null for a name that is not a plain field. */
    static Function<String, ExprType> scopeOf(List<FieldAst> fields) {
        return name -> {
            if (name == null || name.startsWith("$") || name.contains(".") || fields == null) {
                return null;
            }
            for (FieldAst field : fields) {
                if (field != null && field.getName() != null && field.getName().equalsIgnoreCase(name)) {
                    ExprType type = ExprType.fromDslType(field.getType());
                    return type == ExprType.ANY ? null : type;
                }
            }
            return null;
        };
    }

    static void validateInvariant(String entityName, String expression, List<FieldAst> fields, List<String> errors) {
        for (String problem : ExpressionTypeChecker.check(expression, scopeOf(fields),
                ExpressionTypeChecker.Expected.BOOLEAN, false)) {
            errors.add("Entity " + entityName + " invariant expression: " + problem
                    + " -- suggestedFix: use operands of the right type, or convert with addDays/daysBetween/round");
        }
    }

    /**
     * A {@code queries[].where}: boolean, and lowered to the database, so only pushable functions
     * ({@code pushable = true}). {@code $params.x} and {@code $user.x} have no declared type here.
     */
    static void validateQueryWhere(String here, String where, List<FieldAst> fields, List<String> errors) {
        for (String problem : ExpressionTypeChecker.check(where, scopeOf(fields),
                ExpressionTypeChecker.Expected.BOOLEAN, true)) {
            errors.add(here + ": where " + problem
                    + " -- suggestedFix: compare a field with a value of its own type");
        }
    }

    /**
     * A row {@code access.read}/{@code access.write} rule: boolean. Not checked as pushable: a rule the
     * query lowering cannot translate (a role() call, say) is still evaluated, after the load, by the
     * kernel's own evaluator, so a non-pushable function is not an authoring error here.
     */
    static void validateAccessRule(String entityName, String label, String expression, List<FieldAst> fields,
                                   List<String> errors) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        for (String problem : ExpressionTypeChecker.check(expression, scopeOf(fields),
                ExpressionTypeChecker.Expected.BOOLEAN, false)) {
            errors.add("Entity " + entityName + " access." + label + ": " + problem
                    + " -- suggestedFix: compare a field with a value of its own type");
        }
    }

    /** The result of a default/derived expression must also fit the type of the field it fills. */
    static void validateFieldValueExpression(String entityName, FieldAst field, String kind, String expression,
                                             List<FieldAst> fields, List<String> errors) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        ExpressionTypeChecker.Expected expected = switch (ExprType.fromDslType(field.getType())) {
            case NUMBER -> ExpressionTypeChecker.Expected.NUMBER;
            case BOOLEAN -> ExpressionTypeChecker.Expected.BOOLEAN;
            default -> ExpressionTypeChecker.Expected.ANY;
        };
        for (String problem : ExpressionTypeChecker.check(expression, scopeOf(fields), expected, false)) {
            errors.add("Entity " + entityName + " field " + field.getName() + ": " + kind + " " + problem
                    + " -- suggestedFix: use operands of the right type, or convert with addDays/daysBetween/round");
        }
    }
}
