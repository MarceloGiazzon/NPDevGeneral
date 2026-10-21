package com.npdev.runtime.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * REG-269: a leave request ending before it starts was accepted because {@code endDate >= startDate}
 * compared two non-numbers as 0 >= 0. The invariant engine must evaluate it chronologically, whether
 * the dates arrive as ISO strings (a JSON request) or as {@link LocalDate} (a typed entity).
 */
class CelInvariantEngineDateComparisonTest {

    private static CelInvariantEngine engine() {
        List<CompiledField> fields = List.of(
                new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                new CompiledField("startDate", "date", "java.time.LocalDate", false, true, false),
                new CompiledField("endDate", "date", "java.time.LocalDate", false, true, false));
        CompiledConcept request = new CompiledConcept(
                "LeaveRequest", "LeaveRequest", "leave_requests",
                fields,
                List.of(),
                List.of(new CompiledInvariant("EndNotBeforeStart", "expression", null, "endDate >= startDate")));
        Map<String, CompiledConcept> entities = new LinkedHashMap<>();
        entities.put("LeaveRequest", request);
        return CelInvariantEngine.fromCompiledModel(new CompiledModel("hr", "v1", entities));
    }

    private static Map<String, Object> payload(Object start, Object end) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "r-1");
        payload.put("startDate", start);
        payload.put("endDate", end);
        return payload;
    }

    @Test
    void anEndDateBeforeTheStartDateIsAViolationForIsoStrings() {
        List<String> violations = engine().evaluate("LeaveRequest", payload("2026-03-10", "2026-03-09"));
        assertEquals(1, violations.size(), violations.toString());
    }

    @Test
    void anEndDateBeforeTheStartDateIsAViolationForTypedDates() {
        List<String> violations = engine().evaluate("LeaveRequest",
                payload(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 9)));
        assertEquals(1, violations.size(), violations.toString());
    }

    @Test
    void sameDayAndLaterEndDatesPass() {
        assertTrue(engine().evaluate("LeaveRequest", payload("2026-03-10", "2026-03-10")).isEmpty());
        assertTrue(engine().evaluate("LeaveRequest", payload("2026-03-10", "2026-03-20")).isEmpty());
        assertTrue(engine().evaluate("LeaveRequest",
                payload(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2))).isEmpty());
    }
}
