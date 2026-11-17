package com.npdev.dsl.v1.expr;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * REG-284: the functions a {@code defaultExpression}/{@code derivedExpression} may call -- the ones the
 * runtime registers (kernel {@code ValueExpressionFunctions} reads {@link #NAMES} rather than keeping its
 * own copy). FORM P3-T2: the set is now every {@code value} function in {@code functions-stdlib.json}
 * -- string, math, date, {@code now()}/{@code uuid()} and geo -- so {@code now()} and {@code uuid()} also
 * work INSIDE a larger expression; {@code nextNumber('name')} stays a whole-{@code defaultExpression}
 * form. Anything else used to validate clean and then evaluate to its own source text or null at save time.
 */
public final class ValueBehaviorFunctions {

    /** FORM P3-T2: derived from {@code expr/functions-stdlib.json} (kind {@code value}, names and aliases). */
    public static final Set<String> NAMES = StdlibRegistry.implementedNames("value");

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
            // now() / uuid() give a new value on every call: fine once, at create, never on every read.
            if (!defaultExpression && StdlibRegistry.find(function).map(StdlibRegistry.Entry::defaultOnly).orElse(false)) {
                return function;
            }
        }
        return null;
    }
}
