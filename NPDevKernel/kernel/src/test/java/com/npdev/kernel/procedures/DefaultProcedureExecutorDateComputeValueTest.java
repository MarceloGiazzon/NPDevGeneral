package com.npdev.kernel.procedures;

import com.npdev.dsl.v1.expr.DateFunctions;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.EventBus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-313 (b): {@code computeValue} gains the date operations {@code addDays}, {@code daysBetween} and
 * {@code today}, so a procedure can compute "enrolment date + the protocol's day offset" -- the case
 * that used to fail at runtime with {@code COMPUTE_VALUE_INVALID_OPERAND} ({@code add} stays numeric).
 * Same {@link DateFunctions} the expression language uses, so a procedure and a rule agree.
 */
class DefaultProcedureExecutorDateComputeValueTest {

    private static final ExecutionContext CTX = ExecutionContext.of("tenant-a", "actor-a");
    private static final EventBus NOOP_EVENT_BUS = event -> { };

    private static DefaultProcedureExecutor newExecutor() {
        ConceptGateway gateway = new DefaultConceptGateway(new InMemoryConceptStore());
        return new DefaultProcedureExecutor(gateway, (call, state) -> CapabilityResult.success(null), NOOP_EVENT_BUS);
    }

    private static ProcedureExecutionResult run(Map<String, Object> input, ProcedureStep... steps) {
        return newExecutor().execute(new ProcedureDefinition("DateProc", List.of(steps)), input, CTX);
    }

    @Test
    void addDaysPlacesAVisitRelativeToEnrolment() {
        ProcedureExecutionResult result = run(
                Map.of("p", Map.of("enrolmentDate", "2026-10-01"), "vt", Map.of("dayOffset", 84, "windowBefore", 7)),
                ProcedureStep.computeValue("target", "addDays", "$p.enrolmentDate", "$vt.dayOffset", "targetDate"),
                ProcedureStep.computeValue("start", "addDays", "$targetDate", -7, "windowStart"));

        assertTrue(result.ok(), result.failureMessage());
        assertEquals("2026-12-24", result.state().get("targetDate"));
        assertEquals("2026-12-17", result.state().get("windowStart"));
    }

    @Test
    void daysBetweenIsAWholeNumberLong() {
        ProcedureExecutionResult result = run(Map.of(),
                ProcedureStep.computeValue("gap", "daysBetween", "2026-10-01", "2026-12-24", "gap"),
                ProcedureStep.computeValue("more", "add", "$gap", 1, "gapPlusOne"));

        assertTrue(result.ok(), result.failureMessage());
        assertEquals(84L, result.state().get("gap"));
        assertEquals(85L, result.state().get("gapPlusOne"), "daysBetween's result feeds numeric computeValue");
    }

    @Test
    void todayNeedsNoOperandsAndCanBePinned() {
        ProcedureExecutionResult live = run(Map.of(), ProcedureStep.computeValue("t", "today", null, null, "today"));
        assertTrue(live.ok(), live.failureMessage());
        assertEquals(LocalDate.now(ZoneOffset.UTC).toString(), live.state().get("today"));

        ProcedureExecutionResult pinned = DateFunctions.withToday(LocalDate.of(2026, 10, 29), () -> run(Map.of(),
                ProcedureStep.computeValue("t", "today", null, null, "today"),
                ProcedureStep.computeValue("due", "addDays", "$today", 14, "due"),
                ProcedureStep.computeValue("left", "daysBetween", "$today", "$due", "left")));
        assertTrue(pinned.ok(), pinned.failureMessage());
        assertEquals("2026-10-29", pinned.state().get("today"));
        assertEquals("2026-11-12", pinned.state().get("due"));
        assertEquals(14L, pinned.state().get("left"));
    }

    @Test
    void aNonDateOperandFailsWithTheSameClearCodeAsANonNumber() {
        ProcedureExecutionResult result = run(Map.of(),
                ProcedureStep.computeValue("bad", "addDays", "not-a-date", 1, "x"));
        assertFalse(result.ok());
        assertEquals("COMPUTE_VALUE_INVALID_OPERAND", result.failureCode());
        assertTrue(result.failureMessage().contains("not-a-date"), result.failureMessage());
    }

    @Test
    void aFractionalOrMissingDayCountFails() {
        assertEquals("COMPUTE_VALUE_INVALID_OPERAND", run(Map.of(),
                ProcedureStep.computeValue("bad", "addDays", "2026-10-01", 1.5, "x")).failureCode());
        assertEquals("COMPUTE_VALUE_INVALID_OPERAND", run(Map.of("vt", Map.of("dayOffset", "soon")),
                ProcedureStep.computeValue("bad", "addDays", "2026-10-01", "$vt.dayOffset", "x")).failureCode());
    }

    @Test
    void plainAddStillRefusesADateSoTheTwoStayDistinct() {
        ProcedureExecutionResult result = run(Map.of("p", Map.of("enrolmentDate", "2026-10-01")),
                ProcedureStep.computeValue("old", "add", "$p.enrolmentDate", 84, "x"));
        assertFalse(result.ok());
        assertEquals("COMPUTE_VALUE_INVALID_OPERAND", result.failureCode());
    }

    @Test
    void theOperatorsAreCaseInsensitiveLikeAddAndSubtract() {
        ProcedureExecutionResult result = run(Map.of(),
                ProcedureStep.computeValue("c", " AddDays ", "2026-10-01", 1, "x"));
        assertTrue(result.ok(), result.failureMessage());
        assertEquals("2026-10-02", result.state().get("x"));
    }
}
