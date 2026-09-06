package com.npdev.dsl.v1.ast;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-209 (B1 lift): adding the uid-terminal constructor to {@link ConceptAst} and {@link FieldAst}
 * turned their PREVIOUS widest constructor into a delegating-only overload -- production code
 * ({@code JsonModelParser}, {@code ModelCompiler}) now calls the uid-terminal one directly, so
 * nothing else exercises this exact arity unless a test does. Pins that the delegation still
 * produces the same object a direct uid-terminal call would (uid defaulting to null).
 */
class OrphanedConstructorOverloadsTest {

    @Test
    void conceptAstsFormerWidestConstructorDelegatesToTheUidTerminalOneWithANullUid() {
        ConceptAst concept = new ConceptAst(
                "Widget", null, null, List.of(), List.of(), List.of(), null, null, null,
                null, List.of(), null, null, null, null, false, true);

        assertNull(concept.getUid());
        assertTrue(concept.isTemporal());
        assertFalse(concept.isSoftDelete());
    }

    @Test
    void fieldAstsFormerWidestConstructorDelegatesToTheUidTerminalOneWithANullUid() {
        FieldAst field = new FieldAst(
                "sku", "string", false, true, false, List.of(), null, null, null, null,
                List.of(), null, null, null, null, false, null, null);

        assertNull(field.getUid());
        assertTrue(field.isRequired());
    }
}
