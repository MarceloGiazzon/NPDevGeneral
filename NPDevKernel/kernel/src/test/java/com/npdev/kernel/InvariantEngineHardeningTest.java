package com.npdev.kernel;

import com.npdev.kernel.ports.InvariantEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvariantEngineHardeningTest {

    @Test
    void multiInvariantRequestFailsWhenAdapterOnlyImplementsSingleInvariantEvaluation() {
        InvariantEngine singleInvariantOnly = new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of("single-invariant-fail");
            }
        };

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> singleInvariantOnly.evaluate(new InvariantEngine.InvariantEvaluationRequest(
                        "User",
                        Map.of("email", "a@b.com"),
                        List.of("EmailRequired", "EmailUnique"),
                        new InvariantEngine.EvaluationMetadata(
                                "CreateUser",
                                "validate",
                                0,
                                FlowStepDefinition.InvariantCheckpoint.PRE,
                                "corr-1"
                        ),
                        Map.of()
                ))
        );
        assertTrue(error.getMessage().contains("must implement evaluate(List<String>, EvaluationContext)"));
    }

    @Test
    void singleInvariantEvaluationStillMapsViolationToRequestedRef() {
        InvariantEngine singleInvariantOnly = new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of("required");
            }
        };

        InvariantEngine.InvariantEvaluationResult result = singleInvariantOnly.evaluate(
                new InvariantEngine.InvariantEvaluationRequest(
                        "User",
                        Map.of(),
                        List.of("EmailRequired"),
                        new InvariantEngine.EvaluationMetadata(
                                "CreateUser",
                                "validate",
                                0,
                                FlowStepDefinition.InvariantCheckpoint.PRE,
                                "corr-1"
                        ),
                        Map.of()
                )
        );

        assertEquals(1, result.violations().size());
        assertEquals("EmailRequired", result.violations().get(0).invariantRef());
    }

    @Test
    void nullViolationFromAdapterFailsFastForMultiInvariantRequest() {
        InvariantEngine ambiguousAdapter = new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of();
            }

            @Override
            public List<Violation> evaluate(List<String> invariants, EvaluationContext context) {
                return java.util.Collections.singletonList(null);
            }
        };

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> ambiguousAdapter.evaluate(new InvariantEngine.InvariantEvaluationRequest(
                        "User",
                        Map.of("email", "a@b.com"),
                        List.of("EmailRequired", "EmailUnique"),
                        new InvariantEngine.EvaluationMetadata(
                                "CreateUser",
                                "validate",
                                0,
                                FlowStepDefinition.InvariantCheckpoint.PRE,
                                "corr-1"
                        ),
                        Map.of()
                ))
        );
        assertEquals(
                "Invariant engine returned null violation for multi-invariant evaluation",
                error.getMessage()
        );
    }
}

