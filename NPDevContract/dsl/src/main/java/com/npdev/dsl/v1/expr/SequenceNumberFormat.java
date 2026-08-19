package com.npdev.dsl.v1.expr;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R5.3: renders a {@code sequences[].format} template (e.g. {@code "INV-{year}-{seq:4}"}) against
 * an allocated counter value -- the human-readable half of {@code nextNumber('name')}. Pure and
 * side-effect-free: this class knows nothing about persistence or concurrency (that is {@code
 * com.npdev.kernel.ports.SequenceAllocator}'s job); it only answers two questions the allocator
 * and the author-time validator both need -- "is this format well-formed" and "what text does
 * counter value N render as, today".
 *
 * <p>Recognized tokens: {@code {seq}} / {@code {seq:N}} (the running counter, zero-padded to N
 * digits when {@code :N} is given -- exactly one required per format), {@code {year}} (4-digit),
 * {@code {yy}} (2-digit), {@code {month}}, {@code {day}} (both 2-digit, zero-padded). Anything
 * else inside {@code { }} is a validation error naming the format, not a silently-ignored token --
 * a typo'd {@code {yaer}} must fail at author time, not render as literal text forever.
 */
public final class SequenceNumberFormat {

    private SequenceNumberFormat() {
    }

    /** Thrown by {@link #validate(String)} when a format is malformed. */
    public static final class FormatException extends RuntimeException {
        public FormatException(String message) {
            super(message);
        }
    }

    private static final Pattern TOKEN = Pattern.compile("\\{(seq(?::(\\d{1,2}))?|year|yy|month|day)}");
    private static final Pattern ANY_BRACE = Pattern.compile("[{}]");

    /**
     * Validates that {@code format} is non-blank, contains exactly one {@code {seq}}/{@code
     * {seq:N}} token, and has no other {@code { }} content besides recognized tokens.
     *
     * @throws FormatException naming the format and the problem
     */
    public static void validate(String format) {
        if (format == null || format.isBlank()) {
            throw new FormatException("format must be non-blank");
        }
        Matcher matcher = TOKEN.matcher(format);
        int cursor = 0;
        int seqTokenCount = 0;
        while (matcher.find()) {
            rejectStrayBraces(format, format.substring(cursor, matcher.start()));
            if (matcher.group(1).startsWith("seq")) {
                seqTokenCount++;
            }
            cursor = matcher.end();
        }
        rejectStrayBraces(format, format.substring(cursor));
        if (seqTokenCount == 0) {
            throw new FormatException(
                    "format must contain exactly one {seq} or {seq:N} token: " + format);
        }
        if (seqTokenCount > 1) {
            throw new FormatException(
                    "format must contain exactly one {seq} or {seq:N} token, found " + seqTokenCount + ": " + format);
        }
    }

    private static void rejectStrayBraces(String format, String segment) {
        if (ANY_BRACE.matcher(segment).find()) {
            throw new FormatException("format has an unrecognized token near '" + segment + "' in: " + format);
        }
    }

    /**
     * The date-derived partition suffix implied by which date token {@code format} uses -- the
     * FINEST granularity present wins ({@code day} &gt; {@code month} &gt; {@code year}), so the
     * underlying counter resets to 1 exactly when the rendered text would otherwise start
     * repeating a lower number under a new date bucket. Empty when {@code format} has no date
     * token at all, meaning the counter never resets.
     */
    public static String scopeKeySuffix(String format, LocalDate today) {
        if (format == null) {
            return "";
        }
        if (format.contains("{day}")) {
            return "|" + today;
        }
        if (format.contains("{month}")) {
            return "|" + String.format(Locale.ROOT, "%04d-%02d", today.getYear(), today.getMonthValue());
        }
        if (format.contains("{year}") || format.contains("{yy}")) {
            return "|" + today.getYear();
        }
        return "";
    }

    /** Renders {@code format} with counter value {@code seq} and today's date. {@code seq} must be &gt;= 1. */
    public static String render(String format, long seq, LocalDate today) {
        if (seq < 1) {
            throw new IllegalArgumentException("seq must be >= 1, got " + seq);
        }
        Matcher matcher = TOKEN.matcher(format);
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            out.append(format, cursor, matcher.start());
            String token = matcher.group(1);
            if (token.startsWith("seq")) {
                int width = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
                String digits = Long.toString(seq);
                out.append("0".repeat(Math.max(0, width - digits.length()))).append(digits);
            } else if ("year".equals(token)) {
                out.append(String.format(Locale.ROOT, "%04d", today.getYear()));
            } else if ("yy".equals(token)) {
                out.append(String.format(Locale.ROOT, "%02d", today.getYear() % 100));
            } else if ("month".equals(token)) {
                out.append(String.format(Locale.ROOT, "%02d", today.getMonthValue()));
            } else { // "day"
                out.append(String.format(Locale.ROOT, "%02d", today.getDayOfMonth()));
            }
            cursor = matcher.end();
        }
        out.append(format, cursor, format.length());
        return out.toString();
    }
}
