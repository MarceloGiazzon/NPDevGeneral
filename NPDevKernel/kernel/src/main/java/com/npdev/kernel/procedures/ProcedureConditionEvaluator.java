package com.npdev.kernel.procedures;

import java.util.Map;
import java.util.Objects;

/**
 * REG-96 (Wave 0.6, MASTER_AI_PLATFORM_PROGRAMME_v2.md): a procedure's {@code condition}/{@code if}
 * predicate.
 *
 * <p><b>What it used to be.</b> {@code truthy(resolve(state, step.conditionRef()))} -- a bare
 * reference tested for truthiness. It could ask "is this present and non-empty", never "does it
 * equal {@code 'Concluido'}". That is why {@code aggregate.onCommit} could not guard an event
 * emission on a lifecycle transition (M11), and why REG-96's own example -- emitting
 * {@code MovimentoConfirmado} only when a commit leaves the record {@code Concluido} -- was
 * inexpressible: {@code $input.situacao} is truthy for all four of that concept's states.
 *
 * <p><b>What it is now.</b> The SAME closed grammar {@code visibleWhen} already carries, with the
 * procedure's own {@code $ref} resolution as the left-hand side:
 *
 * <pre>
 *   condition := ref | ref op literal
 *   op        := == | != | &gt;= | &lt;= | &gt; | &lt;
 *   literal   := 'text' | number | true | false | ref
 * </pre>
 *
 * <p>Three deliberate choices:
 *
 * <ul>
 *   <li><b>A bare ref keeps its old truthiness meaning</b>, so every existing model behaves
 *       identically. This is an extension, not a replacement -- there is no codemod because there
 *       is nothing to rewrite.</li>
 *   <li><b>The grammar is borrowed, not invented.</b> {@code visibleWhen} (and now {@code $ui.*},
 *       Move 11 W6) already uses {@code <ref> == '<literal>'}; the standing convention is to extend
 *       an existing grammar rather than add a second dialect. Risk R5 in the programme's own table.</li>
 *   <li><b>Both sides may be refs.</b> {@code $a == $b} compares two pieces of procedure state --
 *       the thing that made the equality test need Java in {@code SyncOcupacaoProcedure}
 *       ("procedures' own 'if' step has no comparison-expression grammar", its own description says
 *       so). A right-hand ref that does not resolve is {@code null}, and {@code null == null} is
 *       true, so a typo can still be quiet -- that is X0-6 (REG-100), not solvable here.</li>
 * </ul>
 *
 * <p><b>An unparseable predicate is NOT silently false.</b> It throws, per X0's rule -- a branch
 * that silently takes the else-path on a malformed condition is exactly the class this platform is
 * removing. The one exception is a bare ref, which is not "unparseable" but a legal, pre-existing
 * shape.
 */
final class ProcedureConditionEvaluator {

    private ProcedureConditionEvaluator() {
    }

    /** Thrown when a condition is neither a bare ref nor a well-formed comparison. */
    static final class UnsupportedConditionException extends RuntimeException {
        UnsupportedConditionException(String condition, String reason) {
            super("CONDITION_UNSUPPORTED: cannot evaluate condition \"" + condition + "\" -- " + reason
                    + ". Supported: a bare $ref (truthiness), or <ref> (== | != | > | >= | < | <=) "
                    + "<literal-or-ref>, literal being 'text', a number, or true/false. A malformed "
                    + "condition is refused rather than treated as false: a branch that silently "
                    + "takes the else-path is the defect class this rule exists to remove (REG-96).");
        }
    }

    /** Operators longest-first, so {@code >=} is never mis-read as {@code >}. */
    private static final String[] OPERATORS = {"==", "!=", ">=", "<=", ">", "<"};

    /**
     * @param resolver resolves a {@code $ref} against procedure state -- passed in rather than
     *                 imported so this class stays independent of the executor's private helpers
     */
    static boolean evaluate(String condition, Map<String, Object> state, Resolver resolver) {
        if (condition == null || condition.isBlank()) {
            return false;
        }
        String text = condition.trim();
        for (String operator : OPERATORS) {
            int at = indexOfOutsideQuotes(text, operator);
            if (at < 0) {
                continue;
            }
            String leftText = text.substring(0, at).trim();
            String rightText = text.substring(at + operator.length()).trim();
            if (leftText.isEmpty()) {
                throw new UnsupportedConditionException(condition, "no reference before '" + operator + "'");
            }
            if (rightText.isEmpty()) {
                throw new UnsupportedConditionException(condition, "nothing to compare against after '" + operator + "'");
            }
            Object left = resolver.resolve(state, leftText);
            Object right = operandValue(condition, rightText, state, resolver);
            return compare(condition, operator, left, right);
        }
        // No operator: the pre-existing bare-ref truthiness shape, unchanged.
        return truthy(resolver.resolve(state, text));
    }

    /** A quoted string, a number, a boolean, or another {@code $ref}. */
    private static Object operandValue(String condition, String text, Map<String, Object> state, Resolver resolver) {
        if (text.length() >= 2 && text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\'') {
            return text.substring(1, text.length() - 1);
        }
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        if (text.startsWith("$")) {
            return resolver.resolve(state, text);
        }
        try {
            return text.indexOf('.') >= 0 ? Double.parseDouble(text) : Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            throw new UnsupportedConditionException(condition,
                    "operand \"" + text + "\" is neither a quoted string, a number, a boolean, nor a $ref");
        }
    }

    private static boolean compare(String condition, String operator, Object left, Object right) {
        if ("==".equals(operator)) {
            return Objects.equals(normalize(left), normalize(right));
        }
        if ("!=".equals(operator)) {
            return !Objects.equals(normalize(left), normalize(right));
        }
        // Ordered comparison: an absent operand is FALSE, not an error -- the row/state genuinely
        // does not satisfy "$qty > 5" when there is no qty. Same judgement as the query engine's.
        if (left == null || right == null) {
            return false;
        }
        int comparison = compareValues(left, right);
        return switch (operator) {
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            default -> throw new UnsupportedConditionException(condition, "unknown operator '" + operator + "'");
        };
    }

    private static int compareValues(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        }
        try {
            return Double.compare(
                    Double.parseDouble(String.valueOf(left)), Double.parseDouble(String.valueOf(right)));
        } catch (NumberFormatException ignored) {
            return String.valueOf(left).compareTo(String.valueOf(right));
        }
    }

    private static Object normalize(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value == null ? null : String.valueOf(value);
    }

    private static int indexOfOutsideQuotes(String text, String token) {
        boolean inQuote = false;
        for (int index = 0; index + token.length() <= text.length(); index++) {
            if (text.charAt(index) == '\'') {
                inQuote = !inQuote;
                continue;
            }
            if (!inQuote && text.startsWith(token, index)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof CharSequence text) {
            return !text.toString().isBlank() && !"false".equalsIgnoreCase(text.toString());
        }
        return value != null;
    }

    /** The executor's own {@code resolve(state, ref)}, passed in as a function. */
    @FunctionalInterface
    interface Resolver {
        Object resolve(Map<String, Object> state, String ref);
    }
}
