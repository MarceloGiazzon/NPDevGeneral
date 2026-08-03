package com.npdev.dsl.v1.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4 (roadmap B27, ADR-0011 D1): the groupBy join-path grammar, in isolation from any DSL
 * validation or SQL emission -- proves the parse shapes named in {@link GroupByJoinGrammar}'s own
 * javadoc, plus every malformed-path rejection X0 requires (never a silently-dropped clause).
 */
class GroupByJoinGrammarTest {

    @Test
    void plainFieldParsesAsDirect() {
        GroupByJoinGrammar.Target target = GroupByJoinGrammar.parse("warehouseId");
        assertEquals(new GroupByJoinGrammar.Target.Direct("warehouseId"), target);
    }

    @Test
    void oneHopJoinParsesWithNoContext() {
        GroupByJoinGrammar.Target target = GroupByJoinGrammar.parse("lote.produtoId");
        assertEquals(new GroupByJoinGrammar.Target.Join(null, "lote", "produtoId"), target);
    }

    @Test
    void contextQualifiedJoinParses() {
        GroupByJoinGrammar.Target target = GroupByJoinGrammar.parse("inventory::lote.produtoId");
        assertEquals(new GroupByJoinGrammar.Target.Join("inventory", "lote", "produtoId"), target);
    }

    @Test
    void blankFieldIsRejected() {
        var ex = assertThrows(GroupByJoinGrammar.UnsupportedGroupByPathException.class,
                () -> GroupByJoinGrammar.parse("  "));
        assertTrue(ex.getMessage().contains("non-blank"), ex.getMessage());
    }

    @Test
    void twoJoinHopsAreRejected() {
        var ex = assertThrows(GroupByJoinGrammar.UnsupportedGroupByPathException.class,
                () -> GroupByJoinGrammar.parse("lote.produto.categoria"));
        assertTrue(ex.getMessage().contains("more than one join hop"), ex.getMessage());
    }

    @Test
    void doubleContextSeparatorIsRejected() {
        var ex = assertThrows(GroupByJoinGrammar.UnsupportedGroupByPathException.class,
                () -> GroupByJoinGrammar.parse("inventory::wms::lote.produtoId"));
        assertTrue(ex.getMessage().contains("more than one '::'"), ex.getMessage());
    }

    @Test
    void contextQualifierWithoutJoinHopIsRejected() {
        var ex = assertThrows(GroupByJoinGrammar.UnsupportedGroupByPathException.class,
                () -> GroupByJoinGrammar.parse("inventory::warehouseId"));
        assertTrue(ex.getMessage().contains("requires a join hop"), ex.getMessage());
    }

    @Test
    void emptyReferenceFieldSegmentIsRejected() {
        assertThrows(GroupByJoinGrammar.UnsupportedGroupByPathException.class,
                () -> GroupByJoinGrammar.parse(".produtoId"));
    }

    @Test
    void emptyTargetFieldSegmentIsRejected() {
        assertThrows(GroupByJoinGrammar.UnsupportedGroupByPathException.class,
                () -> GroupByJoinGrammar.parse("lote."));
    }

    @Test
    void nonIdentifierContextIsRejected() {
        assertThrows(GroupByJoinGrammar.UnsupportedGroupByPathException.class,
                () -> GroupByJoinGrammar.parse("123bad::lote.produtoId"));
    }
}
