package com.npdev.runtime.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.expr.DateFunctions;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * REG-313 (b): a rule can be written against a date relative to another date or to today -- the
 * clinical-trial visit window (day 84 +/- 7 from enrolment) was inexpressible. Same functions, same
 * semantics as a field default, because both call {@link DateFunctions}.
 */
class CelInvariantEngineDateFunctionsTest {

    private static CelInvariantEngine engine(String... expressions) {
        List<CompiledField> fields = List.of(
                new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                new CompiledField("enrolmentDate", "date", "java.time.LocalDate", false, true, false),
                new CompiledField("visitDate", "date", "java.time.LocalDate", false, true, false),
                new CompiledField("dueDate", "date", "java.time.LocalDate", false, true, false));
        List<CompiledInvariant> invariants = new java.util.ArrayList<>();
        for (int i = 0; i < expressions.length; i++) {
            invariants.add(new CompiledInvariant("Rule" + i, "expression", null, expressions[i]));
        }
        CompiledConcept visit = new CompiledConcept("Visit", "Visit", "visits", fields, List.of(), invariants);
        Map<String, CompiledConcept> entities = new LinkedHashMap<>();
        entities.put("Visit", visit);
        return CelInvariantEngine.fromCompiledModel(new CompiledModel("trial", "v1", entities));
    }

    private static Map<String, Object> visit(Object enrolment, Object visitDate, Object due) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "v-1");
        payload.put("enrolmentDate", enrolment);
        payload.put("visitDate", visitDate);
        payload.put("dueDate", due);
        return payload;
    }

    private static final String WINDOW =
            "visitDate >= addDays(enrolmentDate, 77) && visitDate <= addDays(enrolmentDate, 91)";

    @Test
    void aVisitInsideDay84PlusMinusSevenPasses() {
        CelInvariantEngine engine = engine(WINDOW);
        assertTrue(engine.evaluate("Visit", visit("2026-10-01", "2026-12-24", null)).isEmpty());
        assertTrue(engine.evaluate("Visit", visit("2026-10-01", "2026-12-17", null)).isEmpty(), "day 77 edge");
        assertTrue(engine.evaluate("Visit", visit("2026-10-01", "2026-12-31", null)).isEmpty(), "day 91 edge");
    }

    @Test
    void aVisitOutsideTheWindowIsAViolation() {
        CelInvariantEngine engine = engine(WINDOW);
        assertEquals(1, engine.evaluate("Visit", visit("2026-10-01", "2026-12-16", null)).size(), "day 76");
        assertEquals(1, engine.evaluate("Visit", visit("2026-10-01", "2027-01-01", null)).size(), "day 92");
    }

    @Test
    void typedDatesWorkAsWellAsIsoStrings() {
        CelInvariantEngine engine = engine(WINDOW);
        assertTrue(engine.evaluate("Visit",
                visit(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 24), null)).isEmpty());
        assertEquals(1, engine.evaluate("Visit",
                visit(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 5), null)).size());
    }

    @Test
    void daysBetweenReadsAsARangeCheck() {
        CelInvariantEngine engine = engine("daysBetween(enrolmentDate, visitDate) <= 91");
        assertTrue(engine.evaluate("Visit", visit("2026-10-01", "2026-12-31", null)).isEmpty());
        assertEquals(1, engine.evaluate("Visit", visit("2026-10-01", "2027-01-01", null)).size());
    }

    @Test
    void todayMakesARuleRelativeToTheWriteDate() {
        CelInvariantEngine engine = engine("dueDate >= today()");
        List<String> late = DateFunctions.withToday(LocalDate.of(2026, 10, 29),
                () -> engine.evaluate("Visit", visit(null, null, "2026-10-28")));
        List<String> onTime = DateFunctions.withToday(LocalDate.of(2026, 10, 29),
                () -> engine.evaluate("Visit", visit(null, null, "2026-10-29")));
        assertEquals(1, late.size(), late.toString());
        assertTrue(onTime.isEmpty(), onTime.toString());
    }

    @Test
    void aMissingOperandCannotSatisfyTheWindow() {
        // same as `endDate >= startDate` with a null startDate (REG-269): an unorderable pair is false.
        assertEquals(1, engine(WINDOW).evaluate("Visit", visit(null, "2026-12-24", null)).size());
    }
}
