package com.npdev.dsl.v1.query;

import com.npdev.dsl.v1.query.PickerFilterGrammar.Clause;
import com.npdev.dsl.v1.query.PickerFilterGrammar.Literal;
import com.npdev.dsl.v1.query.PickerFilterGrammar.Operator;
import com.npdev.dsl.v1.query.PickerFilterGrammar.UnsupportedFilterException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOUNDARY_LIFT_PLAN_2026-09-02.md Wave 4 package 4.3 (B16) Step 1: proves the shared grammar behind
 * {@code field.picker.filter} / {@code transaction.bandPickers.<name>.filter} in isolation -- AND
 * composition, the {@code $root.<field>} reference, and the refused-outright {@code ||}, all
 * unchanged from what {@code AutoPanelExpander.parseBandPickerFilterExpression} and
 * {@code BusinessUiEmitter.parsePickerFilterExpression} each hand-rolled before this move.
 */
class PickerFilterGrammarTest {

    @Test
    void blankExpressionIsEmpty() {
        assertEquals(List.of(), PickerFilterGrammar.parse(null));
        assertEquals(List.of(), PickerFilterGrammar.parse("  "));
    }

    @Test
    void singleQuotedLiteralClauseParses() {
        List<Clause> clauses = PickerFilterGrammar.parse("status == 'Open'");
        assertEquals(1, clauses.size());
        assertEquals(new Clause("status", Operator.EQ, new Literal.Value("Open")), clauses.get(0));
    }

    @Test
    void bareUnquotedLiteralIsAcceptedAsAString() {
        // The two ad hoc parsers this replaces never required quotes -- preserved verbatim.
        List<Clause> clauses = PickerFilterGrammar.parse("status == Open");
        assertEquals(new Literal.Value("Open"), clauses.get(0).literal());
    }

    @Test
    void notEqualsOperatorParses() {
        List<Clause> clauses = PickerFilterGrammar.parse("status != 'Closed'");
        assertEquals(Operator.NEQ, clauses.get(0).operator());
    }

    @Test
    void dollarRowPrefixOnFieldIsStrippedAsANoOp() {
        // Pre-existing convention: "$row." on the FIELD names the picker's own target row, not a
        // reference to any other record -- stripped exactly as the old parsers did.
        List<Clause> clauses = PickerFilterGrammar.parse("$row.status == 'Open'");
        assertEquals("status", clauses.get(0).field());
    }

    @Test
    void andCombinesMultipleClauses() {
        List<Clause> clauses = PickerFilterGrammar.parse("status == 'Open' && region == 'US'");
        assertEquals(2, clauses.size());
        assertEquals(new Clause("status", Operator.EQ, new Literal.Value("Open")), clauses.get(0));
        assertEquals(new Clause("region", Operator.EQ, new Literal.Value("US")), clauses.get(1));
    }

    @Test
    void andInsideAQuotedLiteralIsNotSplit() {
        List<Clause> clauses = PickerFilterGrammar.parse("note == 'a && b'");
        assertEquals(1, clauses.size());
        assertEquals(new Literal.Value("a && b"), clauses.get(0).literal());
    }

    @Test
    void rootReferenceLiteralParses() {
        List<Clause> clauses = PickerFilterGrammar.parse("regionId == $root.regionId");
        assertEquals(new Clause("regionId", Operator.EQ, new Literal.RootReference("regionId")), clauses.get(0));
    }

    @Test
    void rootReferenceComposesWithAStaticClause() {
        List<Clause> clauses = PickerFilterGrammar.parse("status == 'Open' && regionId == $root.regionId");
        assertEquals(2, clauses.size());
        assertEquals(new Literal.Value("Open"), clauses.get(0).literal());
        assertEquals(new Literal.RootReference("regionId"), clauses.get(1).literal());
    }

    @Test
    void orIsRefusedOutright() {
        UnsupportedFilterException failure = assertThrows(UnsupportedFilterException.class,
                () -> PickerFilterGrammar.parse("status == 'Open' || status == 'Pending'"));
        assertTrue(failure.getMessage().contains("'||'"));
        assertTrue(failure.getMessage().contains("visibleWhen"));
    }

    @Test
    void noOperatorInClauseThrows() {
        assertThrows(UnsupportedFilterException.class, () -> PickerFilterGrammar.parse("status 'Open'"));
    }

    @Test
    void emptyClauseBetweenAndOperatorsThrows() {
        assertThrows(UnsupportedFilterException.class, () -> PickerFilterGrammar.parse("status == 'Open' &&  "));
    }

    @Test
    void bareDollarRootWithNoFieldThrows() {
        assertThrows(UnsupportedFilterException.class, () -> PickerFilterGrammar.parse("status == $root."));
    }
}
