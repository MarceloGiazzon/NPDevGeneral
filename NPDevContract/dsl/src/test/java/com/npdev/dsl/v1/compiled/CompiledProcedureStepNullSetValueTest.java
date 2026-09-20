package com.npdev.dsl.v1.compiled;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-224's compiled-model twin: {@code ModelCompiler}/{@code CompiledModelCanonicalJsonReader}
 * both build a {@code CompiledProcedureStep} straight from a plain map, so a fix scoped only to
 * {@code ProcedureStepAst} (the parser-level record) would let a null-valued {@code set} entry
 * survive parsing only to crash again with the exact same {@code Map.copyOf} NPE one layer
 * further down the pipeline, at compile time instead of parse time.
 */
class CompiledProcedureStepNullSetValueTest {

    private static CompiledProcedureStep stepWithSet(Map<String, Object> set) {
        return new CompiledProcedureStep(
                "clear", "patchConcept", null, null, null, null, null, "Romaneio", null,
                Map.of(), "$row.id", null, null, null, null, null,
                Map.of(), List.of(), List.of(), List.of(),
                null, null, Map.of(), set, false, Map.of(), null, null
        );
    }

    @Test
    void setFieldWithAnExplicitNullValueDoesNotThrow() {
        Map<String, Object> set = new HashMap<>();
        set.put("expedicaoId", null);

        CompiledProcedureStep step = assertDoesNotThrow(() -> stepWithSet(set));

        assertTrue(step.set().containsKey("expedicaoId"), "the null-valued key must survive the copy");
        assertNull(step.set().get("expedicaoId"), "the value itself must remain null, not be dropped or coerced");
    }

    @Test
    void copiedSetMapIsStillImmutable() {
        Map<String, Object> set = new HashMap<>();
        set.put("field", null);
        CompiledProcedureStep step = stepWithSet(set);

        try {
            step.set().put("other", "x");
            throw new AssertionError("expected UnsupportedOperationException on the copied map");
        } catch (UnsupportedOperationException expected) {
            // record fields must remain defensively immutable copies, matching every sibling field
        }
    }
}
