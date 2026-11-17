package com.npdev.dsl.v1.expr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * FORM P3-T2: the implementation of every function {@code functions-stdlib.json} marks {@code impl:
 * stdlib}. The five string functions moved here verbatim from the kernel's {@code
 * ValueExpressionFunctions} (same null handling); the date functions delegate to {@link DateFunctions}.
 *
 * <p>Every value function is <b>total</b>: an argument of the wrong shape or count yields {@code null}
 * (a predicate yields {@code false}), never an exception -- the rule {@code concat}/{@code addDays}
 * already follow. The browser twin is {@code npdev-expr.js}; {@code StdlibFunctionsParityTest} replays
 * {@code functions-parity.json} on both.
 */
public final class StdlibFunctions {

    private static final ThreadLocal<Instant> PINNED_NOW = new ThreadLocal<>();
    private static final ThreadLocal<String> PINNED_UUID = new ThreadLocal<>();

    /** The constant {@code uuid()} returns under {@link #withDeterministicVolatiles}. */
    public static final String DETERMINISTIC_UUID =
            UUID.nameUUIDFromBytes("npdev-deterministic-concept-default".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();

    private static final double EARTH_RADIUS_KM = 6371.0;

    private StdlibFunctions() {
    }

    /**
     * Runs {@code body} with {@code now()} at the epoch, {@code uuid()} constant and {@code today()} at the
     * epoch date -- the backfill shadow proof evaluates twice and refuses a backfill whose passes disagree.
     * Nests, always restores.
     */
    public static <T> T withDeterministicVolatiles(Supplier<T> body) {
        Instant previousNow = PINNED_NOW.get();
        String previousUuid = PINNED_UUID.get();
        PINNED_NOW.set(Instant.EPOCH);
        PINNED_UUID.set(DETERMINISTIC_UUID);
        try {
            return DateFunctions.withToday(LocalDate.EPOCH, body);
        } finally {
            restore(PINNED_NOW, previousNow);
            restore(PINNED_UUID, previousUuid);
        }
    }

    private static <V> void restore(ThreadLocal<V> local, V previous) {
        if (previous == null) {
            local.remove();
        } else {
            local.set(previous);
        }
    }

    /** The pure value functions (kind {@code value}) as registry entries, names and aliases both. */
    public static Map<String, ComputedExpression.ExprFunction> valueFunctions() {
        Map<String, ComputedExpression.ExprFunction> out = new LinkedHashMap<>();
        for (String name : StdlibRegistry.implementedNames("value")) {
            out.put(name, (args, vars) -> apply(name, args.stream().map(arg -> arg.eval(vars)).toList()));
        }
        return out;
    }

    /** {@link #valueFunctions()} plus the list functions ({@code filter}, {@code map}), which take a lambda. */
    public static Map<String, ComputedExpression.ExprFunction> allFunctions() {
        Map<String, ComputedExpression.ExprFunction> out = valueFunctions();
        out.put("filter", (args, vars) -> {
            List<Object> kept = new ArrayList<>();
            List<?> items = listArg(args, vars);
            for (Object item : items) {
                if (truthy(args.get(1).invokeLambda(item, vars))) {
                    kept.add(item);
                }
            }
            return kept;
        });
        out.put("map", (args, vars) -> {
            List<Object> mapped = new ArrayList<>();
            for (Object item : listArg(args, vars)) {
                mapped.add(args.get(1).invokeLambda(item, vars));
            }
            return mapped;
        });
        return out;
    }

    public static ComputedExpression.FunctionRegistry registry() {
        return ComputedExpression.FunctionRegistry.of(allFunctions());
    }

    private static List<?> listArg(List<ComputedExpression.Node> args, Map<String, Object> vars) {
        if (args.size() != 2) {
            throw new ComputedExpression.ExpressionException("expected (list, item => expression)");
        }
        Object first = args.get(0).eval(vars);
        return first instanceof java.util.Collection<?> c ? new ArrayList<>(c) : List.of();
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return v != null && !v.toString().isBlank();
    }

    /** Evaluates one value function by name or alias (case-insensitive); null for anything else. */
    public static Object apply(String functionName, List<Object> args) {
        String normalized = functionName == null ? "" : functionName.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "concat" -> {
                if (args.isEmpty() || args.stream().anyMatch(Objects::isNull)) {
                    yield null;
                }
                StringBuilder out = new StringBuilder();
                for (Object arg : args) {
                    out.append(String.valueOf(arg));
                }
                yield out.toString();
            }
            case "coalesce" -> {
                for (Object arg : args) {
                    if (!isMissingValue(arg)) {
                        yield arg;
                    }
                }
                yield null;
            }
            case "trim" -> args.size() == 1 && args.get(0) != null ? String.valueOf(args.get(0)).trim() : null;
            case "upper", "uppercase" -> args.size() == 1 && args.get(0) != null
                    ? String.valueOf(args.get(0)).toUpperCase(Locale.ROOT) : null;
            case "lower", "lowercase" -> args.size() == 1 && args.get(0) != null
                    ? String.valueOf(args.get(0)).toLowerCase(Locale.ROOT) : null;
            case "length" -> args.size() == 1 && args.get(0) != null ? (Object) (long) String.valueOf(args.get(0)).length() : null;
            case "contains" -> args.size() == 2 ? text(args.get(0)) != null && text(args.get(1)) != null
                    && text(args.get(0)).contains(text(args.get(1))) : null;
            case "startswith" -> args.size() == 2 ? text(args.get(0)) != null && text(args.get(1)) != null
                    && text(args.get(0)).startsWith(text(args.get(1))) : null;
            case "round" -> round(args);
            case "floor" -> unary(args, RoundingMode.FLOOR);
            case "ceil" -> unary(args, RoundingMode.CEILING);
            case "abs" -> args.size() == 1 && number(args.get(0)) != null ? ComputedExpression.normalize(number(args.get(0)).abs()) : null;
            case "min" -> extreme(args, true);
            case "max" -> extreme(args, false);
            case "clamp" -> clamp(args);
            case "now" -> args.isEmpty() ? (PINNED_NOW.get() != null ? PINNED_NOW.get() : Instant.now()).toString() : null;
            case "uuid" -> args.isEmpty() ? (PINNED_UUID.get() != null ? PINNED_UUID.get() : UUID.randomUUID().toString()) : null;
            case "today", "adddays", "daysbetween" -> DateFunctions.apply(normalized, args);
            case "addhours" -> addTime(args, ChronoUnit.HOURS);
            case "addminutes" -> addTime(args, ChronoUnit.MINUTES);
            case "addseconds" -> addTime(args, ChronoUnit.SECONDS);
            case "minutesbetween" -> minutesBetween(args);
            case "istoday" -> {
                if (args.size() != 1) {
                    yield null;
                }
                LocalDate day = DateFunctions.toUtcDate(args.get(0));
                yield day != null && day.equals(DateFunctions.today());
            }
            case "distancekm" -> distanceKm(args);
            case "withinkm" -> {
                if (args.size() != 5) {
                    yield null;
                }
                Object distance = distanceKm(args.subList(0, 4));
                BigDecimal limit = number(args.get(4));
                yield distance != null && limit != null && ComputedExpression.dec(distance).compareTo(limit) <= 0;
            }
            default -> null;
        };
    }

    private static String text(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** A number or numeric-looking text as a decimal; null for null, a boolean or anything else. */
    private static BigDecimal number(Object v) {
        if (v == null || v instanceof Boolean || !ComputedExpression.isNumericLike(v)) {
            return null;
        }
        return ComputedExpression.dec(v);
    }

    private static Long whole(Object v) {
        BigDecimal d = number(v);
        if (d == null) {
            return null;
        }
        try {
            return d.longValueExact();
        } catch (ArithmeticException notWhole) {
            return null;
        }
    }

    private static Object round(List<Object> args) {
        if (args.size() != 2) {
            return null;
        }
        BigDecimal value = number(args.get(0));
        Long places = whole(args.get(1));
        if (value == null || places == null || Math.abs(places) > 100) {
            return null;
        }
        return ComputedExpression.normalize(value.setScale(places.intValue(), RoundingMode.HALF_UP));
    }

    private static Object unary(List<Object> args, RoundingMode mode) {
        BigDecimal value = args.size() == 1 ? number(args.get(0)) : null;
        return value == null ? null : ComputedExpression.normalize(value.setScale(0, mode));
    }

    private static Object extreme(List<Object> args, boolean smaller) {
        if (args.size() != 2) {
            return null;
        }
        BigDecimal a = number(args.get(0));
        BigDecimal b = number(args.get(1));
        if (a == null || b == null) {
            return null;
        }
        return ComputedExpression.normalize(smaller ? a.min(b) : a.max(b));
    }

    private static Object clamp(List<Object> args) {
        if (args.size() != 3) {
            return null;
        }
        BigDecimal value = number(args.get(0));
        BigDecimal low = number(args.get(1));
        BigDecimal high = number(args.get(2));
        if (value == null || low == null || high == null || low.compareTo(high) > 0) {
            return null;
        }
        return ComputedExpression.normalize(value.max(low).min(high));
    }

    private static Object addTime(List<Object> args, ChronoUnit unit) {
        if (args.size() != 2) {
            return null;
        }
        Instant at = ComputedExpression.toInstant(args.get(0));
        Long amount = whole(args.get(1));
        if (at == null || amount == null) {
            return null;
        }
        try {
            return at.plus(amount, unit).toString();
        } catch (java.time.DateTimeException | ArithmeticException unrepresentable) {
            return null;
        }
    }

    private static Object minutesBetween(List<Object> args) {
        if (args.size() != 2) {
            return null;
        }
        Instant from = ComputedExpression.toInstant(args.get(0));
        Instant to = ComputedExpression.toInstant(args.get(1));
        return from == null || to == null ? null : (Object) java.time.Duration.between(from, to).toMinutes();
    }

    /**
     * FORM P3-T4: the in-memory twin of the dialect's great-circle SQL -- the same haversine, rounded to
     * metres, or {@code null} when any coordinate is missing or not a number.
     */
    public static BigDecimal distanceKmBetween(Object lat1, Object lng1, Object lat2, Object lng2) {
        Object km = distanceKm(java.util.Arrays.asList(lat1, lng1, lat2, lng2));
        return km == null ? null : ComputedExpression.dec(km);
    }

    /** Haversine, mean Earth radius, rounded to metres (3 decimals of a kilometre). */
    private static Object distanceKm(List<Object> args) {
        if (args.size() != 4) {
            return null;
        }
        BigDecimal lat1 = number(args.get(0));
        BigDecimal lon1 = number(args.get(1));
        BigDecimal lat2 = number(args.get(2));
        BigDecimal lon2 = number(args.get(3));
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) {
            return null;
        }
        double p1 = Math.toRadians(lat1.doubleValue());
        double p2 = Math.toRadians(lat2.doubleValue());
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        double km = 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(h)));
        return ComputedExpression.normalize(BigDecimal.valueOf(Math.round(km * 1000) / 1000.0));
    }

    private static boolean isMissingValue(Object value) {
        if (value == null) {
            return true;
        }
        return value instanceof String text && text.trim().isEmpty();
    }
}
