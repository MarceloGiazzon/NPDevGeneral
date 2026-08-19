package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.InvariantEngine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4.2 (roadmap): {@code GeneratedCrudRuntimeSupport.evaluateOrchestrationCondition} is the
 * "orchestration twin" of {@code KernelRunner.evaluateCondition} -- both used to be hand-rolled
 * {@code ==}/{@code !=}-only matchers, and both now try the {@link
 * com.npdev.dsl.v1.expr.ComputedExpression} grammar first, falling back to the legacy matcher only
 * on a parse/non-boolean failure. This method is private and had NO prior test coverage of any
 * kind (measured 2026-08-18: no test in this module or the kernel referenced it, directly or
 * through an end-to-end orchestration run), so reflection is the same pattern {@link
 * GeneratedCrudRuntimeNamingTest} already uses for this class's other private methods -- an
 * isolated unit test beats adding none.
 */
class GeneratedCrudRuntimeSupportOrchestrationConditionTest {

    private static boolean evaluate(String condition, Map<String, Object> eventPayload) throws Exception {
        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(
                new CompiledModel("demo", "1.0.0", "v1", Map.of()),
                kernelRunner()
        );
        Method method = GeneratedCrudRuntimeSupport.class.getDeclaredMethod(
                "evaluateOrchestrationCondition", String.class, Map.class);
        method.setAccessible(true);
        return (boolean) method.invoke(support, condition, eventPayload);
    }

    @Test
    void logicalAndAndOrderedComparisonDecideTheCondition() throws Exception {
        assertTrue(evaluate("$event.amount > 100 && $event.status == \"Paid\"",
                Map.of("amount", 150, "status", "Paid")));
        assertFalse(evaluate("$event.amount > 100 && $event.status == \"Paid\"",
                Map.of("amount", 50, "status", "Paid")),
                "amount below threshold must fail even though status matches");
        assertFalse(evaluate("$event.amount > 100 && $event.status == \"Paid\"",
                Map.of("amount", 150, "status", "Pending")),
                "&& must require BOTH operands, not just the first");
    }

    /**
     * Regression half: the exact corpus condition strings ({@code canonical-demo},
     * {@code restaurant-saas-multitenant}: {@code "$event.status == \"Completed\""} /
     * {@code "$event.status == \"Paid\""}) still evaluate identically now that ComputedExpression
     * is tried first -- a plain {@code ==} comparison parses and evaluates directly under it.
     */
    @Test
    void existingCorpusOrchestrationConditionsEvaluateIdentically() throws Exception {
        assertTrue(evaluate("$event.status == \"Completed\"", Map.of("status", "Completed")));
        assertFalse(evaluate("$event.status == \"Completed\"", Map.of("status", "Cancelled")));
        assertTrue(evaluate("$event.status == \"Paid\"", Map.of("status", "Paid")));
        assertFalse(evaluate("$event.status == \"Paid\"", Map.of("status", "Unpaid")));
    }

    @Test
    void blankAndLiteralConditionsKeepTheirLegacyMeaning() throws Exception {
        assertTrue(evaluate(null, Map.of()));
        assertTrue(evaluate("", Map.of()));
        assertTrue(evaluate("true", Map.of()));
        assertFalse(evaluate("false", Map.of()));
    }

    private static KernelRunner kernelRunner() {
        return new KernelRunner(
                (EventBus) event -> {
                },
                new InvariantEngine() {
                    @Override
                    public List<String> evaluate(String entityName, Object payload) {
                        return List.of();
                    }
                }
        );
    }
}
