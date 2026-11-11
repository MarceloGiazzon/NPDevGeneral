package com.npdev.kernel.procedures;

import com.npdev.dsl.v1.compiled.CompiledProcedureParameter;
import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.dsl.v1.validation.ComputeValueOperators;
import com.npdev.kernel.CapabilityError;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.concepts.GovernedTestGateways;
import com.npdev.kernel.concepts.GovernedTestGateways.ConceptSpec;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** K1 (computeValue operators), K2 (runQuery params) and K3 (capabilityCall errorTarget). */
class DefaultProcedureExecutorK123Test {

    private static final ExecutionContext CTX = ExecutionContext.of("tenant-a", "actor-a");
    private static final EventBus NOOP_BUS = event -> { };

    // ------------------------------------------------------------------ K1

    private static ProcedureExecutionResult compute(String operator, Object left, Object right) {
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                GovernedTestGateways.forConcepts(ConceptSpec.of("X", "a")), (call, state) -> null, NOOP_BUS);
        return executor.execute(new ProcedureDefinition("C",
                List.of(ProcedureStep.computeValue("c", operator, left, right, "out"))), Map.of(), CTX);
    }

    @Test
    void numericOperators() {
        assertEquals(12L, compute("multiply", 3, 4).state().get("out"));
        assertEquals(7.5, compute("multiply", 3, 2.5).state().get("out"));
        assertEquals(2.5, compute("divide", 5, 2).state().get("out"));
        assertEquals(3L, compute("min", 3, 9).state().get("out"));
        assertEquals(9L, compute("max", 3, 9).state().get("out"));
        assertEquals(3.14, compute("round", 3.14159, 2).state().get("out"));
        assertEquals(3L, compute("round", 2.5, 0).state().get("out"), "HALF_UP");
    }

    @Test
    void divideByZeroIsANamedFailureNeverInfinity() {
        ProcedureExecutionResult result = compute("divide", 1, 0);
        assertFalse(result.ok());
        assertEquals("COMPUTE_VALUE_DIVIDE_BY_ZERO", result.failureCode());
    }

    @Test
    void badRoundPlacesAreRefused() {
        assertEquals("COMPUTE_VALUE_INVALID_OPERAND", compute("round", 1.5, 11).failureCode());
        assertEquals("COMPUTE_VALUE_INVALID_OPERAND", compute("round", 1.5, -1).failureCode());
        assertEquals("COMPUTE_VALUE_INVALID_OPERAND", compute("round", 1.5, 1.5).failureCode());
    }

    @Test
    void everyOperatorTheValidatorAcceptsIsKnownToTheExecutor() {
        for (String operator : ComputeValueOperators.NUMERIC) {
            ProcedureExecutionResult result = compute(operator, 4, 2);
            assertFalse("COMPUTE_VALUE_UNKNOWN_OPERATOR".equals(result.failureCode()),
                    operator + " is accepted by the validator but unknown to the executor");
        }
    }

    // ------------------------------------------------------------------ K2

    private static DefaultConceptGateway seededOrders() {
        DefaultConceptGateway gateway = GovernedTestGateways.forConcepts(ConceptSpec.of("Order", "cliente", "total", "codigo"));
        ExecutionContext ctx = ExecutionContext.of("dev", "operator");
        gateway.save(new ConceptWriteRequest("Order", "o-1", null,
                Map.of("id", "o-1", "cliente", "acme", "total", 30, "codigo", "ACME-1")), ctx);
        gateway.save(new ConceptWriteRequest("Order", "o-2", null,
                Map.of("id", "o-2", "cliente", "other", "total", 10, "codigo", "OTH-2")), ctx);
        return gateway;
    }

    private static ProcedureExecutionResult runWithParams(Map<String, Object> params, Map<String, Object> input) {
        Map<String, CompiledQuery> queries = Map.of("ByClient",
                new CompiledQuery("ByClient", "Order", "cliente == :who", List.of("codigo asc"), null,
                        List.of(new CompiledProcedureParameter("who", "string", true, null, null)),
                        List.of(), Map.of(), List.of(), List.of(), null));
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                seededOrders(), (call, state) -> null, NOOP_BUS, Map.of(), ProcedureExecutionLimits.defaults(), queries);
        ProcedureStep step = ProcedureStep.runQuery("run", "ByClient", "Order", "rows").withQueryParams(params);
        return executor.execute(new ProcedureDefinition("Run", List.of(step)), input, ExecutionContext.of("dev", "operator"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> codigos(ProcedureExecutionResult result) {
        return ((List<ConceptRecord>) result.state().get("rows")).stream()
                .map(r -> String.valueOf(r.data().get("codigo"))).toList();
    }

    @Test
    void boundLiteralAndRefFilter() {
        ProcedureExecutionResult literal = runWithParams(Map.of("who", "acme"), Map.of());
        assertTrue(literal.ok(), literal.failureMessage());
        assertEquals(List.of("ACME-1"), codigos(literal));

        ProcedureExecutionResult ref = runWithParams(Map.of("who", "$input.client"), Map.of("client", "other"));
        assertTrue(ref.ok(), ref.failureMessage());
        assertEquals(List.of("OTH-2"), codigos(ref));
    }

    @Test
    void missingRequiredParameterFailsNeverUnfiltered() {
        ProcedureExecutionResult result = runWithParams(Map.of(), Map.of());
        assertFalse(result.ok(), "a required parameter nobody bound must not return every row");
        assertEquals("QUERY_PREDICATE_UNSUPPORTED", result.failureCode());
    }

    // ------------------------------------------------------------------ K3

    private static ProcedureExecutionResult callWith(CapabilityResult outcome, boolean withErrorTarget) {
        CapabilityDispatcher dispatcher = (call, state) -> outcome;
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                GovernedTestGateways.forConcepts(ConceptSpec.of("X", "a")), dispatcher, NOOP_BUS);
        ProcedureStep call = ProcedureStep.callCapability("call", "psp", "HttpConnector", "http-connector", "charge",
                List.of(), "charged");
        if (withErrorTarget) {
            call = call.withErrorTarget("chargeError");
        }
        return executor.execute(new ProcedureDefinition("Pay", List.of(call)), Map.of("chargeError", "stale"), CTX);
    }

    @Test
    void failureWithErrorTargetSucceedsAndDescribesTheError() {
        ProcedureExecutionResult result = callWith(CapabilityResult.failure("CONNECTOR_HTTP_503", "down",
                CapabilityErrorKind.TRANSIENT, Map.of("httpStatus", 503)), true);
        assertTrue(result.ok(), result.failureMessage());
        assertNull(result.state().get("charged"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) result.state().get("chargeError");
        assertEquals("CONNECTOR_HTTP_503", error.get("code"));
        assertEquals("TRANSIENT", error.get("kind"));
        assertEquals(true, error.get("retryable"));
        assertEquals(503, error.get("httpStatus"));
    }

    @Test
    void aDeclineIsPermanentNotRetryable() {
        ProcedureExecutionResult result = callWith(CapabilityResult.failure("CONNECTOR_HTTP_402", "declined",
                CapabilityErrorKind.PERMANENT, Map.of("httpStatus", 402)), true);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) result.state().get("chargeError");
        assertEquals(false, error.get("retryable"));
    }

    @Test
    void successClearsAStaleError() {
        ProcedureExecutionResult result = callWith(CapabilityResult.success(Map.of("ok", true)), true);
        assertTrue(result.ok());
        assertFalse(result.state().containsKey("chargeError"));
        assertEquals(Map.of("ok", true), result.state().get("charged"));
    }

    @Test
    void withoutErrorTargetAFailureStillFailsTheProcedure() {
        ProcedureExecutionResult result = callWith(CapabilityResult.failure("CONNECTOR_HTTP_503", "down",
                CapabilityErrorKind.TRANSIENT, Map.of("httpStatus", 503)), false);
        assertFalse(result.ok());
        assertEquals("CONNECTOR_HTTP_503", result.failureCode());
    }

    @Test
    void patchConceptSetMayClearAFieldWithAnExplicitNull() {
        java.util.Map<String, Object> set = new java.util.LinkedHashMap<>();
        set.put("driverAccount", null);
        set.put("status", "REQUESTED");
        ProcedureStep step = ProcedureStep.patchConcept("clear", "Ride", "$input.id", set, "ride");
        assertTrue(step.setValues().containsKey("driverAccount"));
        assertNull(step.setValues().get("driverAccount"));
    }
}
