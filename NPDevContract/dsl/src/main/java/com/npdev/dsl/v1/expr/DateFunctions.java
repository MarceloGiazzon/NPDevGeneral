package com.npdev.dsl.v1.expr;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * REG-313 (b): the date functions of the expression language -- {@code today()},
 * {@code addDays(date, n)} and {@code daysBetween(from, to)} -- as ONE pure implementation that every
 * evaluator (field defaults/derived values, invariants, procedures, query parameter defaults) calls, so
 * they cannot drift apart. The DSL owns it, like {@link ValueBehaviorFunctions}, because model
 * validation and every runtime registry need the same names.
 *
 * <p>Semantics, all deliberate:
 * <ul>
 *   <li><b>UTC calendar dates.</b> A date is a day, a date-time is compared by the UTC calendar date it
 *       falls on -- the same reading {@code ComputedExpression}'s temporal comparison already gives
 *       ("a date at 00:00 UTC"). {@code today()} is the current UTC date. There is no per-app time
 *       zone setting to read; one would be a separate, explicit feature.</li>
 *   <li><b>Total.</b> An operand that is not a date, a {@code days} that is not a whole number, a wrong
 *       argument count, or a result outside the representable range yields {@code null}, never an
 *       exception -- the rule {@code concat}/{@code coalesce} already follow.</li>
 *   <li><b>Shape-preserving.</b> {@code addDays} of a date is a date ({@code "2026-12-24"}); of a
 *       date-time it keeps the time of day and the offset. Results are ISO-8601 strings, which is what
 *       a date field carries through the expression language and JSON.</li>
 *   <li><b>{@code today()} can be pinned</b> for the current thread ({@link #withToday}); the backfill
 *       shadow proof uses it to stay deterministic exactly as it does for {@code now()}.</li>
 * </ul>
 */
public final class DateFunctions {

    public static final Set<String> NAMES = Set.of("today", "addDays", "daysBetween");

    private static final Pattern DATE_ONLY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private static final ThreadLocal<LocalDate> PINNED_TODAY = new ThreadLocal<>();

    private DateFunctions() {
    }

    /** The current UTC date, or the date pinned by an enclosing {@link #withToday}. */
    public static LocalDate today() {
        LocalDate pinned = PINNED_TODAY.get();
        return pinned != null ? pinned : LocalDate.now(Clock.systemUTC());
    }

    /** Runs {@code body} with {@code today()} fixed to {@code date} on this thread; nests, always restores. */
    public static <T> T withToday(LocalDate date, Supplier<T> body) {
        LocalDate previous = PINNED_TODAY.get();
        PINNED_TODAY.set(date);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                PINNED_TODAY.remove();
            } else {
                PINNED_TODAY.set(previous);
            }
        }
    }

    /** Evaluates one of {@link #NAMES} (case-insensitive, like {@code ValueExpressionFunctions.apply}); null for anything else. */
    public static Object apply(String functionName, List<Object> args) {
        String normalized = functionName == null ? "" : functionName.trim().toLowerCase(Locale.ROOT);
        try {
            return switch (normalized) {
                case "today" -> args.isEmpty() ? today().toString() : null;
                case "adddays" -> args.size() == 2 ? addDays(args.get(0), args.get(1)) : null;
                case "daysbetween" -> args.size() == 2 ? daysBetween(args.get(0), args.get(1)) : null;
                default -> null;
            };
        } catch (DateTimeException | ArithmeticException | NumberFormatException unrepresentable) {
            return null;
        }
    }

    static Object addDays(Object date, Object days) {
        Long n = wholeNumber(days);
        if (n == null || date == null) {
            return null;
        }
        if (date instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate().plusDays(n).toString();
        }
        if (date instanceof LocalDate localDate) {
            return localDate.plusDays(n).toString();
        }
        if (date instanceof LocalDateTime localDateTime) {
            return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(localDateTime.plusDays(n));
        }
        if (date instanceof OffsetDateTime offset) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(offset.plusDays(n));
        }
        if (date instanceof ZonedDateTime zoned) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zoned.toOffsetDateTime().plusDays(n));
        }
        if (date instanceof Instant instant) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC).plusDays(n));
        }
        if (date instanceof java.util.Date legacy) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(legacy.toInstant().atOffset(ZoneOffset.UTC).plusDays(n));
        }
        if (date instanceof String text) {
            return addDaysToText(text.trim(), n);
        }
        return null;
    }

    private static String addDaysToText(String text, long days) {
        if (DATE_ONLY.matcher(text).matches()) {
            return LocalDate.parse(text).plusDays(days).toString();
        }
        try {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.parse(text).plusDays(days));
        } catch (DateTimeException notOffset) {
            // fall through: a local date-time has no offset
        }
        try {
            return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.parse(text).plusDays(days));
        } catch (DateTimeException notADate) {
            return null;
        }
    }

    /** True when {@code value} is a date or date-time these functions can read (what model validation checks a literal against). */
    public static boolean isDate(Object value) {
        return toUtcDate(value) != null;
    }

    static Long daysBetween(Object from, Object to) {
        LocalDate start = toUtcDate(from);
        LocalDate end = toUtcDate(to);
        return start == null || end == null ? null : ChronoUnit.DAYS.between(start, end);
    }

    /** The UTC calendar date a date or date-time value falls on, or null when it is neither. */
    static LocalDate toUtcDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate();
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof ZonedDateTime zoned) {
            return zoned.withZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof Instant instant) {
            return instant.atOffset(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof java.util.Date legacy) {
            return legacy.toInstant().atOffset(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof String text) {
            return textToUtcDate(text.trim());
        }
        return null;
    }

    private static LocalDate textToUtcDate(String text) {
        if (DATE_ONLY.matcher(text).matches()) {
            return parseOrNull(() -> LocalDate.parse(text));
        }
        LocalDate offset = parseOrNull(() ->
                OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate());
        return offset != null ? offset : parseOrNull(() -> LocalDateTime.parse(text).toLocalDate());
    }

    private static LocalDate parseOrNull(Supplier<LocalDate> parse) {
        try {
            return parse.get();
        } catch (DateTimeException notADate) {
            return null;
        }
    }

    /** {@code value} as a long when it is a {@link Number} with no fractional part, else null. */
    private static Long wholeNumber(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        try {
            BigDecimal decimal = number instanceof BigDecimal big ? big : new BigDecimal(number.toString());
            return decimal.longValueExact();
        } catch (ArithmeticException | NumberFormatException notWhole) {
            return null;
        }
    }
}
