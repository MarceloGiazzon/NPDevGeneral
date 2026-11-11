package com.npdev.dsl.v1.validation;

import java.util.Set;

/**
 * The ONE list of procedure {@code computeValue} operators. The validator reads it; the kernel's executor switch is
 * pinned to it by a test, so the two cannot drift. Lower-cased, as compared.
 */
public final class ComputeValueOperators {

    /** Operators on two numbers. {@code round}: left rounded to {@code right} decimal places (0..10, HALF_UP). */
    public static final Set<String> NUMERIC =
            Set.of("add", "subtract", "multiply", "divide", "min", "max", "round");

    /** Date operators (REG-313 b) -- implemented by the expression language's DateFunctions. */
    public static final Set<String> DATE = Set.of("adddays", "daysbetween", "today");

    public static final String NAMES =
            "[add, subtract, multiply, divide, min, max, round, addDays, daysBetween, today]";

    private ComputeValueOperators() {
    }
}
