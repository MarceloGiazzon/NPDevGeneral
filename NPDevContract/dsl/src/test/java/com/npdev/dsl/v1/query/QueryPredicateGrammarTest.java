package com.npdev.dsl.v1.query;

import com.npdev.dsl.v1.query.QueryPredicateGrammar.PredicateClause;
import com.npdev.dsl.v1.query.QueryPredicateGrammar.PredicateLiteral;
import com.npdev.dsl.v1.query.QueryPredicateGrammar.PredicateOperator;
import com.npdev.dsl.v1.query.QueryPredicateGrammar.UnsupportedPredicateException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4.3 (Roadmap Wave 1), reshaped by FORM P3-T4: proves {@link QueryPredicateGrammar#parseGroups} --
 * a query {@code where} written in the one expression language and lowered to OR-groups of AND-combined
 * clauses (IN, contains/startsWith, null checks, a reference-path left side bounded at
 * {@link GroupByJoinGrammar#MAX_JOIN_HOPS} hops, {@code $params.name} and {@code $user.*} on the right).
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
        List<List<PredicateClause>> groups = QueryPredicateGrammar.parseGroups("status in ['ACTIVE', 'CLOSED', 'PENDING']");
        PredicateClause clause = groups.get(0).get(0);
        assertEquals(PredicateOperator.IN, clause.operator());
        assertEquals(new PredicateLiteral.Values(List.of(
                new PredicateLiteral.Value("ACTIVE"),
                new PredicateLiteral.Value("CLOSED"),
                new PredicateLiteral.Value("PENDING"))), clause.literal());
    }

    @Test
    void inListAcceptsAMixOfPlaceholderAndLiteralValues() {
        List<List<PredicateClause>> groups = QueryPredicateGrammar.parseGroups("status in [$params.first, 'CLOSED']");
        PredicateLiteral.Values values = (PredicateLiteral.Values) groups.get(0).get(0).literal();
        assertEquals(List.of(new PredicateLiteral.Placeholder("first"), new PredicateLiteral.Value("CLOSED")),
                values.values());
    }

    @Test
    void inWithoutAListIsRejected() {
        var ex = assertThrows(UnsupportedPredicateException.class,
                () -> QueryPredicateGrammar.parseGroups("status in 'ACTIVE'"));
        assertTrue(ex.getMessage().contains("list literal"), ex.getMessage());
    }

    @Test
    void notInBecomesAnAndOfNotEquals() {
        List<List<PredicateClause>> groups = QueryPredicateGrammar.parseGroups("status not in ['A', 'B']");
        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).size());
        assertEquals(PredicateOperator.NEQ, groups.get(0).get(0).operator());
        assertEquals(PredicateOperator.NEQ, groups.get(0).get(1).operator());
    }

    /**
     * REG-315 used to refuse {@code (a || b) && c}. The expression language has parentheses, so the
     * lowering distributes && over || into OR-groups.
     */
    @Test
    void parenthesesDistributeIntoOrGroups() {
        List<List<PredicateClause>> groups =
                QueryPredicateGrammar.parseGroups("(status == 'A' || status == 'B') && region == 'EU'");
        assertEquals(2, groups.size());
        assertEquals(2, groups.get(0).size());
        assertEquals(2, groups.get(1).size());
        assertEquals(new PredicateLiteral.Value("A"), groups.get(0).get(0).literal());
        assertEquals(new PredicateLiteral.Value("B"), groups.get(1).get(0).literal());
        assertEquals(new PredicateLiteral.Value("EU"), groups.get(1).get(1).literal());
    }

    @Test
    void notIsPushedDownByDeMorgan() {
        // !(a == 1 && b > 2)  ==  a != 1 || b <= 2
        List<List<PredicateClause>> groups = QueryPredicateGrammar.parseGroups("!(a == 1 && b > 2)");
        assertEquals(2, groups.size());
        assertEquals(PredicateOperator.NEQ, groups.get(0).get(0).operator());
        assertEquals(PredicateOperator.LTE, groups.get(1).get(0).operator());
        // a bare boolean field, and its negation
        assertEquals(new PredicateLiteral.Value(false),
                QueryPredicateGrammar.parseGroups("!active").get(0).get(0).literal());
        // a literal on the left is the same comparison, mirrored
        assertEquals(PredicateOperator.LT, QueryPredicateGrammar.parseGroups("5 > qty").get(0).get(0).operator());
    }

    @Test
    void aFunctionThatCannotBePushedDownIsRefusedByName() {
        var ex = assertThrows(UnsupportedPredicateException.class,
                () -> QueryPredicateGrammar.parseGroups("upper(status) == 'ACTIVE'"));
        assertTrue(ex.getMessage().contains("function 'upper'"), ex.getMessage());
        var arithmetic = assertThrows(UnsupportedPredicateException.class,
                () -> QueryPredicateGrammar.parseGroups("qty + 1 > 5"));
        assertTrue(arithmetic.getMessage().contains("cannot be pushed down"), arithmetic.getMessage());
    }

    @Test
    void aLongOrChainIsCappedToo() {
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            where.append(i == 0 ? "" : " || ").append("a == ").append(i);
        }
        var ex = assertThrows(UnsupportedPredicateException.class, () -> QueryPredicateGrammar.parseGroups(where.toString()));
        assertTrue(ex.getMessage().contains("more than 64 OR-groups"), ex.getMessage());
    }

    @Test
    void numbersBeyondLongStayExactAndNegativesParse() {
        assertEquals(new PredicateLiteral.Value(new java.math.BigDecimal("99999999999999999999")),
                QueryPredicateGrammar.parseGroups("qty > 99999999999999999999").get(0).get(0).literal());
        assertEquals(new PredicateLiteral.Value(-5L),
                QueryPredicateGrammar.parseGroups("qty > -5").get(0).get(0).literal());
        assertEquals(new PredicateLiteral.Value(1.5d),
                QueryPredicateGrammar.parseGroups("price == 1.50").get(0).get(0).literal());
    }

    @Test
    void negationCasesAndOperandOrientation() {
        // double negation cancels
        assertEquals(PredicateOperator.EQ, QueryPredicateGrammar.parseGroups("!!(a == 1)").get(0).get(0).operator());
        // !(x in [..]) is an AND of !=, !(x not in [..]) is an IN
        assertEquals(2, QueryPredicateGrammar.parseGroups("!(x in ['a','b'])").get(0).size());
        assertEquals(PredicateOperator.IN, QueryPredicateGrammar.parseGroups("!(x not in ['a','b'])").get(0).get(0).operator());
        // a negated contains cannot be pushed down
        var refused = assertThrows(UnsupportedPredicateException.class,
                () -> QueryPredicateGrammar.parseGroups("!contains(name, 'x')"));
        assertTrue(refused.getMessage().contains("negated contains"), refused.getMessage());
        // null on the left is the same null check; a bare literal is not a condition
        assertEquals(PredicateOperator.IS_NULL, QueryPredicateGrammar.parseGroups("null == x").get(0).get(0).operator());
        assertThrows(UnsupportedPredicateException.class, () -> QueryPredicateGrammar.parseGroups("true"));
        assertThrows(UnsupportedPredicateException.class, () -> QueryPredicateGrammar.parseGroups("null < x"));
    }

    @Test
    void referencedPathsAreListedInWrittenOrderWithNoLiteralsOrDollarNames() {
        assertEquals(List.of(new GroupByJoinGrammar.Target.Direct("a"), new GroupByJoinGrammar.Target.Direct("b"),
                        new GroupByJoinGrammar.Target.Join(null, List.of("c"), "d")),
                QueryPredicateGrammar.referencedPaths(
                        "a == $params.x && b not in ['p','q'] || contains(c.d, $user.id)"));
        // one field written once is one reference even though `not in` lowers to two clauses
        assertEquals(1, QueryPredicateGrammar.referencedPaths("status not in ['A','B']").size());
        // a literal on the left does not move the field
        assertEquals(List.of(new GroupByJoinGrammar.Target.Direct("qty")),
                QueryPredicateGrammar.referencedPaths("5 > qty"));
        assertEquals(List.of(), QueryPredicateGrammar.referencedPaths("  "));
    }

    @Test
    void theV1ViewAcceptsOnlyAnAndOfPlainComparisons() {
        List<QueryPredicateGrammar.Clause> clauses = QueryPredicateGrammar.parse("a == 1 && b >= $params.min");
        assertEquals(2, clauses.size());
        assertEquals(new QueryPredicateGrammar.Literal.Placeholder("min"), clauses.get(1).literal());
        assertEquals(List.of(), QueryPredicateGrammar.parse(null));
        for (String outside : new String[] {"a == 1 || b == 2", "x in ['a']", "a.b == 1", "contains(n, 'x')", "x == null"}) {
            assertThrows(UnsupportedPredicateException.class, () -> QueryPredicateGrammar.parse(outside), outside);
        }
    }

    @Test
    void withinKmLowersToADistanceClauseWithFieldAndValueCoordinates() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups(
                "withinKm(lat, lng, $params.lat, $params.lng, $params.radius)").get(0).get(0);
        assertEquals(PredicateOperator.DISTANCE_KM, clause.operator());
        assertEquals(new GroupByJoinGrammar.Target.Direct("lat"), clause.path());
        PredicateLiteral.Distance distance = (PredicateLiteral.Distance) clause.literal();
        assertEquals(PredicateLiteral.DistanceCmp.LTE, distance.cmp());
        assertEquals(new PredicateLiteral.DistanceOperand.Field(new GroupByJoinGrammar.Target.Direct("lng")), distance.lng1());
        assertEquals(new PredicateLiteral.DistanceOperand.Value(new PredicateLiteral.Placeholder("lat")), distance.lat2());
        assertEquals(new PredicateLiteral.Placeholder("radius"), distance.km());
        // negated: NOT (distance <= km) is distance > km
        assertEquals(PredicateLiteral.DistanceCmp.GT, ((PredicateLiteral.Distance)
                QueryPredicateGrammar.parseGroups("!withinKm(lat, lng, 1, 2, 5)").get(0).get(0).literal()).cmp());
    }

    @Test
    void distanceKmComparisonsMirrorAndRefuseEquality() {
        assertEquals(PredicateLiteral.DistanceCmp.LT, ((PredicateLiteral.Distance) QueryPredicateGrammar
                .parseGroups("distanceKm(lat, lng, 1, 2) < 10").get(0).get(0).literal()).cmp());
        // a literal on the left is the same comparison, mirrored
        assertEquals(PredicateLiteral.DistanceCmp.LT, ((PredicateLiteral.Distance) QueryPredicateGrammar
                .parseGroups("10 > distanceKm(lat, lng, 1, 2)").get(0).get(0).literal()).cmp());
        // negation flips the comparison
        assertEquals(PredicateLiteral.DistanceCmp.GTE, ((PredicateLiteral.Distance) QueryPredicateGrammar
                .parseGroups("!(distanceKm(lat, lng, 1, 2) < 10)").get(0).get(0).literal()).cmp());
        for (String refused : new String[] {
                "distanceKm(lat, lng, 1, 2) == 10", "distanceKm(lat, lng, 1) < 10", "withinKm(lat, lng, 1, 2)",
                "withinKm(lat, lng, null, 2, 5)", "withinKm(lat, lng, 1, 2, qty)"}) {
            assertThrows(UnsupportedPredicateException.class, () -> QueryPredicateGrammar.parseGroups(refused), refused);
        }
    }

    @Test
    void aDistanceOfOnlyValuesHasNoFieldAndUsesTheSentinelPath() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups("withinKm(1, 2, $params.a, $params.b, 5)").get(0).get(0);
        assertEquals(new GroupByJoinGrammar.Target.Direct(QueryPredicateGrammar.DISTANCE_SENTINEL_FIELD), clause.path());
        // and the sentinel is not a field the author wrote
        assertEquals(List.of(), QueryPredicateGrammar.referencedPaths("withinKm(1, 2, $params.a, $params.b, 5)"));
        assertEquals(List.of(new GroupByJoinGrammar.Target.Direct("lat"), new GroupByJoinGrammar.Target.Direct("lng")),
                QueryPredicateGrammar.referencedPaths("withinKm(lat, lng, 1, 2, 5)"));
    }

    @Test
    void theOldColonPlaceholderSpellingPointsAtParams() {
        var ex = assertThrows(UnsupportedPredicateException.class,
                () -> QueryPredicateGrammar.parseGroups("storeId == :storeId"));
        assertTrue(ex.getMessage().contains("$params.name"), ex.getMessage());
    }

    @Test
    void anExplosiveConditionIsCapped() {
        // 7 independent two-way ORs ANDed together expand to 128 groups > MAX_GROUPS
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            where.append(i == 0 ? "" : " && ").append("(a").append(i).append(" == 1 || b").append(i).append(" == 2)");
        }
        var ex = assertThrows(UnsupportedPredicateException.class, () -> QueryPredicateGrammar.parseGroups(where.toString()));
        assertTrue(ex.getMessage().contains("more than 64 OR-groups"), ex.getMessage());
    }

    @Test
    void aBadReferencePathIsReportedAsAPredicatePathNotAGroupByField() {
        var ex = assertThrows(UnsupportedPredicateException.class,
                () -> QueryPredicateGrammar.parseGroups("a.b.c.d.e == 'x'"));

        assertTrue(ex.getMessage().contains("predicate path \"a.b.c.d.e\""), ex.getMessage());
        assertFalse(ex.getMessage().contains("cannot parse groupBy field"), ex.getMessage());
    }

    @Test
    void parenthesesInsideAnInListAndQuotedLiteralsStillParse() {
        assertEquals(1, QueryPredicateGrammar.parseGroups("status in ['A', 'B'] && note == 'x (y)'").size());
    }

    @Test
    void emptyInListIsRejected() {
        var ex = assertThrows(UnsupportedPredicateException.class,
                () -> QueryPredicateGrammar.parseGroups("status in []"));
        assertTrue(ex.getMessage().contains("at least one value"), ex.getMessage());
    }

    @Test
    void containsParses() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups("contains(displayName, 'widget')").get(0).get(0);
        assertEquals(PredicateOperator.CONTAINS, clause.operator());
        assertEquals(new PredicateLiteral.Value("widget"), clause.literal());
    }

    @Test
    void startsWithParses() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups("startsWith(sku, 'WID-')").get(0).get(0);
        assertEquals(PredicateOperator.STARTS_WITH, clause.operator());
        assertEquals(new PredicateLiteral.Value("WID-"), clause.literal());
    }

    @Test
    void isNullParses() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups("purchaseOrderFile == null").get(0).get(0);
        assertEquals(PredicateOperator.IS_NULL, clause.operator());
        assertEquals(PredicateLiteral.None.INSTANCE, clause.literal());
        assertEquals(new GroupByJoinGrammar.Target.Direct("purchaseOrderFile"), clause.path());
    }

    @Test
    void isNotNullParses() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups("purchaseOrderFile != null").get(0).get(0);
        assertEquals(PredicateOperator.IS_NOT_NULL, clause.operator());
        assertEquals(PredicateLiteral.None.INSTANCE, clause.literal());
    }

    /** Pigmentampas 2026-10-07: "capId != null" was refused with no hint that "is not null" exists. */
    @Test
    void equalsNullAndNotEqualsNullAreNullChecks() {
        PredicateClause notNull = QueryPredicateGrammar.parseGroups("capId != null").get(0).get(0);
        assertEquals(PredicateOperator.IS_NOT_NULL, notNull.operator());
        assertEquals(PredicateLiteral.None.INSTANCE, notNull.literal());
        assertEquals(new GroupByJoinGrammar.Target.Direct("capId"), notNull.path());
        PredicateClause isNull = QueryPredicateGrammar.parseGroups("capId == null").get(0).get(0);
        assertEquals(PredicateOperator.IS_NULL, isNull.operator());
        // A quoted 'null' stays the four-letter string.
        PredicateClause text = QueryPredicateGrammar.parseGroups("note == 'null'").get(0).get(0);
        assertEquals(PredicateOperator.EQ, text.operator());
        assertEquals(new PredicateLiteral.Value("null"), text.literal());
    }

    @Test
    void isNotNullIsNotMisreadAsIsNull() {
        // "is not null" contains "is null" is NOT a substring relationship the other way, but this
        // pins the intended precedence explicitly: the longer suffix must be checked first.
        PredicateClause clause = QueryPredicateGrammar.parseGroups("note != null").get(0).get(0);
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
    void aTwoHopPathIsAReferenceChainNotAContextQualifiedOne() {
        // The "context::" qualifier of groupBy paths has no spelling in the expression language; a dotted
        // path in a where is always referenceField(.referenceField)*.targetField.
        PredicateClause clause = QueryPredicateGrammar.parseGroups("billing.invoice.status == 'PENDING'").get(0).get(0);
        assertEquals(new GroupByJoinGrammar.Target.Join(null, List.of("billing", "invoice"), "status"), clause.path());
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
        PredicateClause clause = QueryPredicateGrammar.parseGroups("contains(discount, '50%_off')").get(0).get(0);
        assertEquals(new PredicateLiteral.Value("50%_off"), clause.literal());
    }

    @Test
    void combinedOrReferencePathAndDateComparisonParsesInOneWhere() {
        // The roadmap item's own done-when shape: OR-groups + a reference path + a date comparison
        // (dates compile through the same ':name'/literal grammar as any other value -- a date
        // literal is supplied bound, same as today's v1 grammar; this predicate uses a placeholder).
        List<List<PredicateClause>> groups = QueryPredicateGrammar.parseGroups(
                "shipment.invoice.status == 'PENDING' || succeeded == false && shipment.invoice.dueDate < $params.cutoff");
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
    void userReferenceParsesAsAUserRefLiteralAndOtherDollarReferencesStayRefused() {
        PredicateClause clause = QueryPredicateGrammar.parseGroups("ownerUsername == $user.id").get(0).get(0);
        assertEquals(new PredicateLiteral.UserRef("$user.id"), clause.literal());
        PredicateClause inList = QueryPredicateGrammar.parseGroups("tenant in [$user.tenantId, 'shared']").get(0).get(0);
        assertEquals(new PredicateLiteral.UserRef("$user.tenantId"),
                ((PredicateLiteral.Values) inList.literal()).values().get(0));
        UnsupportedPredicateException refused = assertThrows(UnsupportedPredicateException.class,
                () -> QueryPredicateGrammar.parseGroups("ownerUsername == $user.roles"));
        assertTrue(refused.getMessage().contains("$user.id"), refused.getMessage());
    }
}
