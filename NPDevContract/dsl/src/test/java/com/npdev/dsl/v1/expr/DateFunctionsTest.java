package com.npdev.dsl.v1.expr;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REG-313 (b): {@code today()}, {@code addDays(date, n)}, {@code daysBetween(from, to)}. Pure, total
 * (an operand that is not a date / not a whole number yields null, never an exception -- the same rule
 * as {@code concat}), and UTC-calendar based like every other temporal comparison in the language.
 */
class DateFunctionsTest {

    private static Object call(String name, Object... args) {
        return DateFunctions.apply(name, Arrays.asList(args));
    }

    @Test
    void todayIsTheUtcCalendarDateAsAnIsoString() {
        Object today = call("today");
        assertEquals(LocalDate.now(java.time.ZoneOffset.UTC).toString(), today);
    }

    @Test
    void todayCanBePinnedForTheCurrentThreadOnly() {
        Object pinned = DateFunctions.withToday(LocalDate.of(2000, 1, 1), () -> call("today"));
        assertEquals("2000-01-01", pinned);
        assertEquals(LocalDate.now(java.time.ZoneOffset.UTC).toString(), call("today"),
                "the pin must not outlive withToday");
    }

    @Test
    void thePinIsRestoredEvenWhenTheBodyThrows() {
        assertThrows(IllegalStateException.class, () -> DateFunctions.withToday(LocalDate.of(2000, 1, 1), () -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals(LocalDate.now(java.time.ZoneOffset.UTC).toString(), call("today"));
    }

    @Test
    void pinsNest() {
        Object inner = DateFunctions.withToday(LocalDate.of(2000, 1, 1),
                () -> DateFunctions.withToday(LocalDate.of(2001, 2, 3), () -> call("today")));
        assertEquals("2001-02-03", inner);
    }

    @Test
    void addDaysOnADateKeepsItADate() {
        assertEquals("2026-12-24", call("addDays", "2026-10-01", 84));
        assertEquals("2026-09-24", call("addDays", "2026-10-01", -7));
        assertEquals("2026-10-01", call("addDays", "2026-10-01", 0));
        assertEquals("2024-02-29", call("addDays", "2024-02-28", 1));
        assertEquals("2025-03-01", call("addDays", "2025-02-28", 1));
        assertEquals("2026-10-02", call("addDays", LocalDate.of(2026, 10, 1), 1L));
    }

    @Test
    void addDaysOnADatetimeKeepsTheTimeOfDay() {
        assertEquals("2026-10-02T10:15:30Z", call("addDays", "2026-10-01T10:15:30Z", 1));
        assertEquals("2026-10-02T10:15:30", call("addDays", "2026-10-01T10:15:30", 1));
        assertEquals("2026-10-08T10:15:30+02:00", call("addDays", "2026-10-01T10:15:30+02:00", 7));
    }

    @Test
    void daysBetweenIsTargetMinusStart() {
        assertEquals(84L, call("daysBetween", "2026-10-01", "2026-12-24"));
        assertEquals(-7L, call("daysBetween", "2026-10-08", "2026-10-01"));
        assertEquals(0L, call("daysBetween", "2026-10-01", "2026-10-01"));
        assertEquals(366L, call("daysBetween", "2023-03-01", "2024-03-01"));
        assertEquals(365L, call("daysBetween", "2024-03-01", "2025-03-01"));
    }

    @Test
    void daysBetweenOfDatetimesUsesTheUtcCalendarDate() {
        assertEquals(1L, call("daysBetween", "2026-10-01T23:59:59Z", "2026-10-02T00:00:01Z"));
        assertEquals(0L, call("daysBetween", "2026-10-01T00:00:01Z", "2026-10-01T23:59:59Z"));
        // 01:00+03:00 on the 2nd is 22:00Z on the 1st
        assertEquals(0L, call("daysBetween", "2026-10-01", "2026-10-02T01:00:00+03:00"));
        assertEquals(3L, call("daysBetween", "2026-10-01", "2026-10-04T12:00:00"));
    }

    @Test
    void daysAcceptAnyWholeNumberAndNothingElse() {
        assertEquals("2026-10-04", call("addDays", "2026-10-01", 3L));
        assertEquals("2026-10-04", call("addDays", "2026-10-01", 3.0));
        assertEquals("2026-10-04", call("addDays", "2026-10-01", new java.math.BigDecimal("3.00")));
        assertNull(call("addDays", "2026-10-01", 1.5));
        assertNull(call("addDays", "2026-10-01", "3"));
        assertNull(call("addDays", "2026-10-01", null));
        assertNull(call("addDays", "2026-10-01", Double.NaN));
        assertNull(call("addDays", "2026-10-01", 1.0e18));
    }

    @Test
    void anythingThatIsNotADateIsNull() {
        for (Object notADate : new Object[] {null, "", "  ", "garbage", "2026-13-01", "2026-02-30", "10/01/2026", 20261001, true}) {
            assertNull(call("addDays", notADate, 1), "addDays(" + notADate + ")");
            assertNull(call("daysBetween", notADate, "2026-10-01"), "daysBetween(" + notADate + ", d)");
            assertNull(call("daysBetween", "2026-10-01", notADate), "daysBetween(d, " + notADate + ")");
        }
    }

    @Test
    void wrongArityIsNullNotAnException() {
        assertNull(call("addDays"));
        assertNull(call("addDays", "2026-10-01"));
        assertNull(call("addDays", "2026-10-01", 1, 2));
        assertNull(call("daysBetween", "2026-10-01"));
        assertNull(call("today", "extra"));
    }

    @Test
    void namesAreTheThreeTheLedgerAsked() {
        assertEquals(java.util.Set.of("today", "addDays", "daysBetween"), DateFunctions.NAMES);
    }

    @Test
    void applyIsCaseInsensitiveOnTheNameLikeTheValueFunctions() {
        assertEquals("2026-10-02", DateFunctions.apply("adddays", List.of("2026-10-01", 1)));
        assertEquals("2026-10-02", DateFunctions.apply(" ADDDAYS ", List.of("2026-10-01", 1)));
        assertNull(DateFunctions.apply("nope", List.of()));
    }
}
