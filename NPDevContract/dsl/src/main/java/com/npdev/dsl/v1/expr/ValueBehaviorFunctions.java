package com.npdev.dsl.v1.expr;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * REG-284: the functions a {@code defaultExpression}/{@code derivedExpression} may call -- the ones the
 * runtime registers (kernel {@code ValueExpressionFunctions} reads {@link #NAMES} rather than keeping its
 * own copy): the five string functions plus REG-313's {@link DateFunctions#NAMES} date functions.
 * {@code now()}, {@code uuid()} and {@code nextNumber('name')} are not registered functions:
 * the runtime recognises each only as a WHOLE {@code defaultExpression}. Anything else used to validate
 * clean and then evaluate to its own source text or null at save time.
 */
public final class ValueBehaviorFunctions {

    public static final Set<String> NAMES = Stream.concat(
            Stream.of("concat", "coalesce", "trim", "uppercase", "lowercase"),
            DateFunctions.NAMES.stream()).collect(Collectors.toUnmodifiableSet());

    /** Sorted, for error messages. */
    public static final List<String> NAMES_SORTED = NAMES.stream().sorted().toList();

    private static final Pattern WHOLE_DEFAULT_FORM =
            Pattern.compile("^(?:now\\(\\s*\\)|uuid\\(\\s*\\)|nextNumber\\(\\s*['\"][^'\"]*['\"]\\s*\\))$");

    private ValueBehaviorFunctions() {
    }

    /**
     * The first function {@code expression} calls that the runtime cannot evaluate in that position, or
     * null. {@code defaultExpression} additionally accepts the whole-expression forms above.
     * Throws {@link ComputedExpression.ExpressionException} if the expression does not parse.
     */
    public static String firstUnavailable(String expression, boolean defaultExpression) {
        String trimmed = expression == null ? "" : expression.trim();
        if (defaultExpression && WHOLE_DEFAULT_FORM.matcher(trimmed).matches()) {
            return null;
        }
        for (String function : ComputedExpression.functionCalls(trimmed)) {
            if (!NAMES.contains(function)) {
                return function;
            }
        }
        return null;
    }
}
