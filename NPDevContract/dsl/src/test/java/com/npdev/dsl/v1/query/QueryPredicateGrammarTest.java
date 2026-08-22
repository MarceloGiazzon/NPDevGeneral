package com.npdev.dsl.v1.query;

import com.npdev.dsl.v1.query.QueryPredicateGrammar.PredicateClause;
import com.npdev.dsl.v1.query.QueryPredicateGrammar.PredicateLiteral;
import com.npdev.dsl.v1.query.QueryPredicateGrammar.PredicateOperator;
import com.npdev.dsl.v1.query.QueryPredicateGrammar.UnsupportedPredicateException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4.3 (Roadmap Wave 1): proves {@link QueryPredicateGrammar#parseGroups} -- the v2 predicate
 * grammar (OR-groups, IN, contains/startsWith, is-null/is-not-null, and a reference-path left side
 * bounded at {@link GroupByJoinGrammar#MAX_JOIN_HOPS} hops) -- in isolation, the same way
 * {@link GroupByJoinGrammarTest} proves the join-path grammar it reuses. See
 * {@code QueryPredicateGrammar}'s own "PREDICATE GRAMMAR V2" section header for why this is not
 * (yet) wired into {@code PackValidation} or {@code ConceptQueryPredicateCompiler.compile()}.
 */
class QueryPredicateGrammarTest {

    @Test
    void blankWhereIsEmpty() {
        assertEquals(List.of(), QueryPredicateGrammar.parseGroups(null));
        assertEquals(List.of(), QueryPredicateGrammar.parseGroups("  "));
    }

    @Test
    void singlePlainComparisonParsesAsOneGroupOneClause() {
        List<List<PredicateClause>> groups = QueryPredicateGrammar.parseGroups("status == 'ACTIVE'");
        assertEquals(1, groups.size());
        assertEquals(1, groups.get(0).size());
        PredicateClause clause = groups.get(0).get(0);
        assertEquals(new GroupByJoinGrammar.Target.Direct("status"), clause.path());
        assertEquals(PredicateOperator.EQ, clause.operator());
        assertEquals(new PredicateLiteral.Value("ACTIVE"), clause.literal());
    }

    @Test
    void orSplitsIntoTwoGroups() {
        List<List<PredicateClause>> groups = QueryPredicateGrammar.parseGroups("status == 'A' || status == 'B'");
        assertEquals(2, groups.size());
        assertEquals(1, groups.get(0).size());
        assertEquals(1, groups.get(1).size());
    }

    @Test
    void andBindsTighterThanOrWithNoParensNeeded() {
        // DNF, standard precedence: "a && b || c" == "(a && b) || c" -- two OR-groups, the first
        // carrying two AND-combined clauses, the second carrying one.
        List<List<PredicateClause>> groups =
                QueryPredicateGrammar.parseGroups("status == 'ACTIVE' && qty > 5 || status == 'CLOSED'");
        assertEquals(2, groups.size());
        assertEquals(2, groups.get(0).size());
        assertEquals(1, groups.get(1).size());
        assertEquals(PredicateOperator.GT, groups.get(0).get(1).operator());
    }

    @Test
    void inListParsesAllValuesInOrder() {
        List<List<PredicateClause>> groups = QueryPredicateGrammar.parseGroups("status in ('ACTIVE', 'CLOSED', 'PENDING')");
        PredicateClause clause = groups.get(0).get(0);
        assertEquals(PredicateOperator.IN, clause.operator());
        assertEquals(new PredicateLiteral.Values(List.of(
                new PredicateLiteral.Value("ACTIVE"),
                new PredicateLiteral.Value("CLOSED"),
                new PredicateLiteral.Value("PENDING"))), clause.literal());
    }

    @Test
    void inListAcceptsAMixOfPlaceholderAndLiteralValues() {
        List<List<PredicateClause>> groups = QueryPredicateGrammar.parseGroups("status in (:first, 'CLOSED')");
        PredicateLiteral.Values values = (PredicateLiteral.Values) groups.get(0).get(0).literal();
        assertEquals(List.of(new PredicateLiteral.Placeholder("first"), new PredicateLiteral.Value("CLOSED")),
                values.values());
    }

    @Test
    void inWithoutParensIsRejected() {
        var ex = assertThrows(UnsupportedPredicateException.class,
                () -> QueryPredicateGrammar.parseGroups("status in 'ACTIVE'"));
        assertTrue(ex.getMessage().contains("parenthesized"), ex.getMessage());
    }

    @Test
    void emptyInListIsRejected() {
        var ex = assertThrows(UnsupportedPredicateException.class,
                () -> QueryPredicateGrammar.parseGroups("status in ()"));
        assertTrue(ex.getMessage().contains("at least one value"), ex.getMessage());
    }

    @Test
    void containsParses() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups("displayName contains 'widget'").get(0).get(0);
        assertEquals(PredicateOperator.CONTAINS, clause.operator());
        assertEquals(new PredicateLiteral.Value("widget"), clause.literal());
    }

    @Test
    void startsWithParses() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups("sku startsWith 'WID-'").get(0).get(0);
        assertEquals(PredicateOperator.STARTS_WITH, clause.operator());
        assertEquals(new PredicateLiteral.Value("WID-"), clause.literal());
    }

    @Test
    void isNullParses() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups("purchaseOrderFile is null").get(0).get(0);
        assertEquals(PredicateOperator.IS_NULL, clause.operator());
        assertEquals(PredicateLiteral.None.INSTANCE, clause.literal());
        assertEquals(new GroupByJoinGrammar.Target.Direct("purchaseOrderFile"), clause.path());
    }

    @Test
    void isNotNullParses() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups("purchaseOrderFile is not null").get(0).get(0);
        assertEquals(PredicateOperator.IS_NOT_NULL, clause.operator());
        assertEquals(PredicateLiteral.None.INSTANCE, clause.literal());
    }

    @Test
    void isNotNullIsNotMisreadAsIsNull() {
        // "is not null" contains "is null" is NOT a substring relationship the other way, but this
        // pins the intended precedence explicitly: the longer suffix must be checked first.
        PredicateClause clause = QueryPredicateGrammar.parseGroups("note is not null").get(0).get(0);
        assertEquals(PredicateOperator.IS_NOT_NULL, clause.operator());
    }

    @Test
    void oneHopReferencePathParsesAsJoin() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups("shipment.carrier == 'DHL'").get(0).get(0);
        assertEquals(new GroupByJoinGrammar.Target.Join(null, List.of("shipment"), "carrier"), clause.path());
    }

    @Test
    void threeHopReferencePathAtTheCapParses() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups("a.b.c.d == 1").get(0).get(0);
        assertEquals(new GroupByJoinGrammar.Target.Join(null, List.of("a", "b", "c"), "d"), clause.path());
    }

    @Test
    void fourHopReferencePathExceedsTheCapAndNamesThePath() {
        var ex = assertThrows(UnsupportedPredicateException.class,
                () -> QueryPredicateGrammar.parseGroups("a.b.c.d.e == 1"));
        assertTrue(ex.getMessage().contains("a.b.c.d.e"), ex.getMessage());
        assertTrue(ex.getMessage().contains("exceeds the cap of 3"), ex.getMessage());
    }

    @Test
    void contextQualifiedReferencePathParses() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups("billing::invoice.status == 'PENDING'").get(0).get(0);
        assertEquals(new GroupByJoinGrammar.Target.Join("billing", List.of("invoice"), "status"), clause.path());
    }

    @Test
    void aFieldNameContainingTheWordInIsNotMisreadAsTheInOperator() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups("domainId == 5").get(0).get(0);
        assertEquals(new GroupByJoinGrammar.Target.Direct("domainId"), clause.path());
        assertEquals(PredicateOperator.EQ, clause.operator());
    }

    @Test
    void aQuotedLiteralContainingOrAndAndTokensIsNotTornApart() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups("note == 'a || b && c'").get(0).get(0);
        assertEquals(new PredicateLiteral.Value("a || b && c"), clause.literal());
    }

    @Test
    void aLiteralContainingLikeWildcardCharactersRoundTripsVerbatim() {
        // Grammar-level proof that the raw string is captured as-is, with no wildcard/escaping
        // interpretation at parse time -- escaping is a SqlDialect concern (SqlDialect#containsPattern),
        // applied when a bound parameter is built, never here.
        PredicateClause clause = QueryPredicateGrammar.parseGroups("discount contains '50%_off'").get(0).get(0);
        assertEquals(new PredicateLiteral.Value("50%_off"), clause.literal());
    }

    @Test
    void combinedOrReferencePathAndDateComparisonParsesInOneWhere() {
        // The roadmap item's own done-when shape: OR-groups + a reference path + a date comparison
        // (dates compile through the same ':name'/literal grammar as any other value -- a date
        // literal is supplied bound, same as today's v1 grammar; this predicate uses a placeholder).
        List<List<PredicateClause>> groups = QueryPredicateGrammar.parseGroups(
                "shipment.invoice.status == 'PENDING' || succeeded == false && shipment.invoice.dueDate < :cutoff");
        assertEquals(2, groups.size());
        assertEquals(1, groups.get(0).size());
        assertEquals(new GroupByJoinGrammar.Target.Join(null, List.of("shipment", "invoice"), "status"),
                groups.get(0).get(0).path());
        assertEquals(2, groups.get(1).size());
        PredicateClause dateClause = groups.get(1).get(1);
        assertEquals(new GroupByJoinGrammar.Target.Join(null, List.of("shipment", "invoice"), "dueDate"),
                dateClause.path());
        assertEquals(PredicateOperator.LT, dateClause.operator());
        assertEquals(new PredicateLiteral.Placeholder("cutoff"), dateClause.literal());
    }

    @Test
    void emptyGroupBetweenOrOperatorsIsRejected() {
        assertThrows(UnsupportedPredicateException.class, () -> QueryPredicateGrammar.parseGroups("status == 'A' || "));
    }

    @Test
    void emptyClauseBetweenAndOperatorsIsRejected() {
        assertThrows(UnsupportedPredicateException.class, () -> QueryPredicateGrammar.parseGroups("status == 'A' && "));
    }

    @Test
    void noSupportedOperatorIsRejected() {
        var ex = assertThrows(UnsupportedPredicateException.class,
                () -> QueryPredicateGrammar.parseGroups("upper(status) 'ACTIVE'"));
        assertTrue(ex.getMessage().contains("no supported"), ex.getMessage());
    }
}
