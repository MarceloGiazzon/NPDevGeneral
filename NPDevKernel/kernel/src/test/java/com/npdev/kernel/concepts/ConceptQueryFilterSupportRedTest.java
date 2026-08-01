package com.npdev.kernel.concepts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LC-P0's RED/GREEN pair (MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 0.3; the finding is
 * MOVE10_AI_LOWCODE_PLAN-2026-07-31.md Part A). The plan's instruction was to reproduce the RED
 * FIRST, with real output, because <i>"if that RED does not reproduce, the P0 finding is wrong and
 * Wave 0 collapses"</i>.
 *
 * <p><b>It reproduced -- and the finding was incomplete in a way that matters.</b> The finding, and
 * the class's own javadoc, describe ONE failure mode: <i>"a clause outside this shape is left
 * unenforced (rows pass through unfiltered)"</i>. Driving it turned up THREE, and the headline claim
 * ("a declared 2-clause where returns every row") was <b>wrong</b> -- a 2-clause AND returned
 * <b>zero</b> rows:
 *
 * <table>
 *   <tr><th>predicate</th><th>author intent</th><th>BEFORE</th><th>AFTER</th></tr>
 *   <tr><td>{@code qty > 5}</td><td>r2, r4</td><td>ALL 4 (unenforced)</td><td>r2, r4</td></tr>
 *   <tr><td>{@code status == 'ACTIVE' && warehouseId == 'W1'}</td><td>r1</td><td>NONE</td><td>r1</td></tr>
 *   <tr><td>{@code status == 'ACTIVE' && qty != 5}</td><td>r2</td><td>ALL 4 (inverted)</td><td>r2</td></tr>
 *   <tr><td>{@code warehouse_id == 'W1'} (typo)</td><td>error</td><td>NONE, silently</td><td>NONE — see note</td></tr>
 * </table>
 *
 * <p>Recording all three matters for the fix's own DoD: a change that made the 2-clause case return
 * "every row" would have looked like progress against the finding as written, while still being
 * wrong. The failure modes disagree with each other, which is exactly why none of them was noticed.
 *
 * <p><b>Scope of the fix, stated so this test is not read as claiming more than it proves.</b> This
 * is LC-P0's CORRECTNESS half: predicates are compiled by {@link ConceptQueryPredicateCompiler} and
 * anything uncompilable throws. Evaluation is still in the JVM over already-fetched records --
 * routing {@code PanelRuntime} and {@code runQuery} through {@code ConceptGateway.query} so the
 * store narrows (WHERE/LIMIT in SQL) is the remaining step, and no assertion here speaks to it.
 */
class ConceptQueryFilterSupportRedTest {

    private static final List<ConceptRecord> ROWS = List.of(
            row("r1", "ACTIVE", "W1", 5),
            row("r2", "ACTIVE", "W2", 9),
            row("r3", "CLOSED", "W1", 5),
            row("r4", "CLOSED", "W2", 9));

    private static ConceptRecord row(String id, String status, String warehouseId, int qty) {
        return new ConceptRecord("Order", id, "t1",
                Map.of("status", status, "warehouseId", warehouseId, "qty", qty));
    }

    private static List<String> ids(List<ConceptRecord> records) {
        return records.stream().map(ConceptRecord::id).toList();
    }

    @Test
    @DisplayName("the single-clause shape that always worked still works -- unchanged behaviour, not a rewrite")
    void singleClauseEqualityStillWorks() {
        assertEquals(List.of("r1", "r2"), ids(ConceptQueryFilterSupport.applyWhere(ROWS, "status == 'ACTIVE'")));
        assertEquals(List.of("r3", "r4"), ids(ConceptQueryFilterSupport.applyWhere(ROWS, "status != 'ACTIVE'")));
        assertEquals(List.of("r1", "r2", "r3", "r4"), ids(ConceptQueryFilterSupport.applyWhere(ROWS, null)),
                "no predicate declared is still no filter -- absent is not the same as failed");
    }

    @Test
    @DisplayName("was mode 1 (unenforced, every row): a comparison operator now actually filters")
    void comparisonOperatorNowFilters() {
        assertEquals(List.of("r2", "r4"), ids(ConceptQueryFilterSupport.applyWhere(ROWS, "qty > 5")));
        assertEquals(List.of("r1", "r3"), ids(ConceptQueryFilterSupport.applyWhere(ROWS, "qty <= 5")));
        assertEquals(List.of("r2", "r4"), ids(ConceptQueryFilterSupport.applyWhere(ROWS, "qty >= 9")),
                ">= must not be mis-read as > -- operators are matched longest-first");
    }

    @Test
    @DisplayName("was mode 2 (ZERO rows): a 2-clause AND now returns exactly the intersection")
    void twoClauseAndNowIntersects() {
        assertEquals(List.of("r1"),
                ids(ConceptQueryFilterSupport.applyWhere(ROWS, "status == 'ACTIVE' && warehouseId == 'W1'")));
        assertEquals(List.of("r2"),
                ids(ConceptQueryFilterSupport.applyWhere(ROWS, "status == 'ACTIVE' && qty > 5")));
        assertEquals(List.of("r4"),
                ids(ConceptQueryFilterSupport.applyWhere(ROWS, "status == 'CLOSED' && warehouseId == 'W2' && qty >= 9")),
                "three clauses, all AND-combined");
    }

    @Test
    @DisplayName("was mode 3 (inverted, every row): a != in a later clause no longer hijacks the whole predicate")
    void notEqualsInALaterClauseNoLongerHijacks() {
        assertEquals(List.of("r2"), ids(ConceptQueryFilterSupport.applyWhere(ROWS, "status == 'ACTIVE' && qty != 5")));
    }

    @Test
    @DisplayName("a literal containing '&&' is not torn in half -- the same class of mistake the old scan made with quotes")
    void andInsideAQuotedLiteralIsNotASeparator() {
        List<ConceptRecord> notes = List.of(
                new ConceptRecord("Note", "n1", "t1", Map.of("text", "a && b")),
                new ConceptRecord("Note", "n2", "t1", Map.of("text", "plain")));
        assertEquals(List.of("n1"), ids(ConceptQueryFilterSupport.applyWhere(notes, "text == 'a && b'")));
    }

    @Test
    @DisplayName("THE POINT OF LC-P0: an uncompilable predicate is a NAMED ERROR, never a default answer")
    void uncompilablePredicateThrowsInsteadOfGuessing() {
        for (String where : new String[]{
                "status in ('ACTIVE','CLOSED')",          // unsupported operator
                "status == 'ACTIVE' || status == 'CLOSED'", // OR: ConceptQuery is AND-combined by contract
                "upper(status) == 'ACTIVE'",              // function on the left
                "order.status == 'ACTIVE'",               // nested path
                "status == ACTIVE",                       // unquoted, non-numeric literal
                "status == $ctx.status",                  // unsubstituted $-reference
                "status ==",                              // no literal
                "== 'ACTIVE'"}) {                         // no field
            ConceptQueryPredicateCompiler.UnsupportedPredicateException thrown = assertThrows(
                    ConceptQueryPredicateCompiler.UnsupportedPredicateException.class,
                    () -> ConceptQueryFilterSupport.applyWhere(ROWS, where),
                    "must refuse rather than answer: " + where);
            assertTrue(thrown.getMessage().startsWith("QUERY_PREDICATE_UNSUPPORTED"),
                    "the error must be named so a caller can map it: " + thrown.getMessage());
            assertTrue(thrown.getMessage().contains(where),
                    "and must quote the predicate it refused: " + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("a $-reference gets its own explanation, because it is the most likely author mistake")
    void dollarReferenceSaysWhatToDo() {
        ConceptQueryPredicateCompiler.UnsupportedPredicateException thrown = assertThrows(
                ConceptQueryPredicateCompiler.UnsupportedPredicateException.class,
                () -> ConceptQueryFilterSupport.applyWhere(ROWS, "warehouseId == $ctx.wh"));
        assertTrue(thrown.getMessage().contains("substitute it before compiling"), thrown.getMessage());
    }

    @Test
    @DisplayName("a typo'd field name still yields no rows -- named here as a KNOWN residual, not a claim")
    void unknownFieldStillSilentlyMatchesNothing() {
        // Deliberately asserting the residual rather than hiding it. `warehouse_id` is a plain,
        // compilable field name; this class cannot know the concept's schema, so it cannot tell a
        // typo from a genuinely absent value. Catching it needs the field checked against the
        // concept at VALIDATION time -- the same shape Move 11 W6 used for $ui.<name>, and the same
        // shape REG-100 names for $root.<field>. Filed there; not solvable at this layer.
        assertEquals(List.of(), ids(ConceptQueryFilterSupport.applyWhere(ROWS, "warehouse_id == 'W1'")));
    }
}
