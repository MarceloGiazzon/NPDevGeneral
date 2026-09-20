package com.npdev.dsl.v1.ast;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-224: {@code Map.copyOf}/{@code Map.ofEntries} reject null VALUES, but a procedure step
 * field like {@code patchConcept.set} can legitimately declare an explicit JSON null (e.g.
 * clearing a reference field back to null). Found on WmsOffice's Z1-Z6 reset procedure, where
 * this crashed model parsing with a bare, contextless NullPointerException.
 */
class ProcedureStepAstNullSetValueTest {

    @Test
    void setFieldWithAnExplicitNullValueDoesNotThrow() {
        Map<String, Object> set = new HashMap<>();
        set.put("expedicaoId", null);

        ProcedureStepAst step = assertDoesNotThrow(() -> new ProcedureStepAst(
                "clear", "patchConcept", null, null, null, null, null, "Romaneio", null,
                Map.of(), "$row.id", null, null, null, null, null,
                Map.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                null, null, Map.of(), set, false, Map.of(), null, null
        ));

        assertTrue(step.set().containsKey("expedicaoId"), "the null-valued key must survive the copy");
        assertNull(step.set().get("expedicaoId"), "the value itself must remain null, not be dropped or coerced");
    }

    @Test
    void copiedSetMapIsStillImmutable() {
        Map<String, Object> set = new HashMap<>();
        set.put("field", null);
        ProcedureStepAst step = new ProcedureStepAst(
                "clear", "patchConcept", null, null, null, null, null, "Romaneio", null,
                Map.of(), "$row.id", null, null, null, null, null,
                Map.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                null, null, Map.of(), set, false, Map.of(), null, null
        );

        try {
            step.set().put("other", "x");
            throw new AssertionError("expected UnsupportedOperationException on the copied map");
        } catch (UnsupportedOperationException expected) {
            // record fields must remain defensively immutable copies, matching every sibling field
        }
    }
}
