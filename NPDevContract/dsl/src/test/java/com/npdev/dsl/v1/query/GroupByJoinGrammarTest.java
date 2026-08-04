package com.npdev.dsl.v1.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4 (roadmap B27, ADR-0011 D1) + S8 W1.1 (roadmap deferred item #1): the groupBy join-path
 * grammar, in isolation from any DSL validation or SQL emission -- proves the parse shapes named in
 * {@link GroupByJoinGrammar}'s own javadoc, plus every malformed-path rejection X0 requires (never a
 * silently-dropped clause).
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
        assertEquals(new GroupByJoinGrammar.Target.Join(null, List.of("lote"), "produtoId"), target);
    }

    @Test
    void contextQualifiedJoinParses() {
        GroupByJoinGrammar.Target target = GroupByJoinGrammar.parse("inventory::lote.produtoId");
        assertEquals(new GroupByJoinGrammar.Target.Join("inventory", List.of("lote"), "produtoId"), target);
    }

    @Test
    void blankFieldIsRejected() {
        var ex = assertThrows(GroupByJoinGrammar.UnsupportedGroupByPathException.class,
                () -> GroupByJoinGrammar.parse("  "));
        assertTrue(ex.getMessage().contains("non-blank"), ex.getMessage());
    }

    /** S8 W1.1: two hops used to be refused outright; now it's within the cap. */
    @Test
    void twoHopJoinParses() {
        GroupByJoinGrammar.Target target = GroupByJoinGrammar.parse("lote.produto.categoria");
        assertEquals(new GroupByJoinGrammar.Target.Join(null, List.of("lote", "produto"), "categoria"), target);
    }

    /** S8 W1.1: three hops is exactly at the cap ({@link GroupByJoinGrammar#MAX_JOIN_HOPS}). */
    @Test
    void threeHopJoinAtTheCapParses() {
        GroupByJoinGrammar.Target target = GroupByJoinGrammar.parse("a.b.c.d");
        assertEquals(new GroupByJoinGrammar.Target.Join(null, List.of("a", "b", "c"), "d"), target);
    }

    /** S8 W1.1: a context qualifier on a multi-hop join still names the FINAL joined concept. */
    @Test
    void contextQualifiedTwoHopJoinParses() {
        GroupByJoinGrammar.Target target = GroupByJoinGrammar.parse("billing::lote.produto.categoria");
        assertEquals(new GroupByJoinGrammar.Target.Join("billing", List.of("lote", "produto"), "categoria"), target);
    }

    /** S8 W1.1: four hops exceeds the cap -- a named compile error, never silently truncated. */
    @Test
    void fourJoinHopsExceedTheCapAndAreRejected() {
        var ex = assertThrows(GroupByJoinGrammar.UnsupportedGroupByPathException.class,
                () -> GroupByJoinGrammar.parse("a.b.c.d.e"));
        assertTrue(ex.getMessage().contains("exceeds the cap of 3"), ex.getMessage());
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
