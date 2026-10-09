package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.compiled.CompiledProcedureParameter;
import com.npdev.dsl.v1.query.GroupByJoinGrammar;
import com.npdev.dsl.v1.query.QueryPredicateGrammar.PredicateOperator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4.3 (Roadmap Wave 1): proves {@link ConceptQueryPredicateCompiler#compilePredicate} -- the v2
 * sibling of {@link ConceptQueryPredicateCompiler#compile(String, List, Map)} -- resolves
 * {@code :name} placeholders (including inside an {@code in (...)} list) the same X0-disciplined
 * way v1 does, and reuses {@link GroupByJoinGrammar}'s hop resolution for a reference-path clause's
 * left side.
 */
class ConceptQueryPredicateCompilerPredicateTest {

    @Test
    void resolvesAPlainClauseWithNoPlaceholder() {
        List<List<ConceptQueryPredicateCompiler.ResolvedClause>> groups =
                ConceptQueryPredicateCompiler.compilePredicate("status == 'ACTIVE'");
        assertEquals(1, groups.size());
        ConceptQueryPredicateCompiler.ResolvedClause clause = groups.get(0).get(0);
        assertEquals(new GroupByJoinGrammar.Target.Direct("status"), clause.path());
        assertEquals(PredicateOperator.EQ, clause.operator());
        assertEquals("ACTIVE", clause.value());
    }

    @Test
    void resolvesAPlaceholderAgainstDeclaredAndBoundParameters() {
        List<CompiledProcedureParameter> parameters =
                List.of(new CompiledProcedureParameter("storeId", "uuid", true, null, null));
        List<List<ConceptQueryPredicateCompiler.ResolvedClause>> groups = ConceptQueryPredicateCompiler.compilePredicate(
                "storeId == :storeId", parameters, Map.of("storeId", "store-a"));
        assertEquals("store-a", groups.get(0).get(0).value());
    }

    @Test
    void resolvesEveryValueInsideAnInListIncludingAMixedPlaceholder() {
        List<CompiledProcedureParameter> parameters =
                List.of(new CompiledProcedureParameter("first", "string", true, null, null));
        List<List<ConceptQueryPredicateCompiler.ResolvedClause>> groups = ConceptQueryPredicateCompiler.compilePredicate(
                "status in (:first, 'CLOSED')", parameters, Map.of("first", "ACTIVE"));
        ConceptQueryPredicateCompiler.ResolvedClause clause = groups.get(0).get(0);
        assertEquals(PredicateOperator.IN, clause.operator());
        assertEquals(List.of("ACTIVE", "CLOSED"), clause.value());
    }

    @Test
    void unboundPlaceholderInsideAnInListIsRefusedNotDefaultedToNull() {
        List<CompiledProcedureParameter> parameters =
                List.of(new CompiledProcedureParameter("first", "string", true, null, null));
        ConceptQueryPredicateCompiler.UnsupportedPredicateException thrown = assertThrows(
                ConceptQueryPredicateCompiler.UnsupportedPredicateException.class,
                () -> ConceptQueryPredicateCompiler.compilePredicate(
                        "status in (:first, 'CLOSED')", parameters, Map.of()));
        assertTrue(thrown.getMessage().contains(":first"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("not supplied a value"), thrown.getMessage());
    }

    @Test
    void undeclaredPlaceholderIsRefused() {
        ConceptQueryPredicateCompiler.UnsupportedPredicateException thrown = assertThrows(
                ConceptQueryPredicateCompiler.UnsupportedPredicateException.class,
                () -> ConceptQueryPredicateCompiler.compilePredicate("storeId == :storeId"));
        assertTrue(thrown.getMessage().contains("storeId"), thrown.getMessage());
    }

    @Test
    void isNullClauseCarriesNoValue() {
        List<List<ConceptQueryPredicateCompiler.ResolvedClause>> groups =
                ConceptQueryPredicateCompiler.compilePredicate("note is null");
        ConceptQueryPredicateCompiler.ResolvedClause clause = groups.get(0).get(0);
        assertEquals(PredicateOperator.IS_NULL, clause.operator());
        assertEquals(null, clause.value());
    }

    @Test
    void referencePathClauseResolvesLikeAPlainOne() {
        List<List<ConceptQueryPredicateCompiler.ResolvedClause>> groups =
                ConceptQueryPredicateCompiler.compilePredicate("shipment.invoice.status == 'PENDING'");
        ConceptQueryPredicateCompiler.ResolvedClause clause = groups.get(0).get(0);
        assertEquals(new GroupByJoinGrammar.Target.Join(null, List.of("shipment", "invoice"), "status"), clause.path());
        assertEquals("PENDING", clause.value());
    }

    @Test
    void aGrammarLevelFailurePropagatesAsUnsupportedPredicateException() {
        ConceptQueryPredicateCompiler.UnsupportedPredicateException thrown = assertThrows(
                ConceptQueryPredicateCompiler.UnsupportedPredicateException.class,
                () -> ConceptQueryPredicateCompiler.compilePredicate("a.b.c.d.e == 1"));
        assertTrue(thrown.getMessage().contains("exceeds the cap of 3"), thrown.getMessage());
    }

    @Test
    void blankWhereCompilesToNoGroups() {
        assertEquals(List.of(), ConceptQueryPredicateCompiler.compilePredicate(null));
        assertEquals(List.of(), ConceptQueryPredicateCompiler.compilePredicate("  "));
    }

    @Test
    void userReferenceResolvesToTheCallerAndAnonymousBecomesIsNull() {
        com.npdev.kernel.ExecutionContext tito = com.npdev.kernel.ExecutionContext.of("t1", "tito");
        ConceptQueryPredicateCompiler.ResolvedClause mine = ConceptQueryPredicateCompiler.compilePredicate(
                "ownerUsername == $user.id", List.of(), Map.of(), tito).get(0).get(0);
        assertEquals(PredicateOperator.EQ, mine.operator());
        assertEquals("tito", mine.value());
        assertEquals("t1", ConceptQueryPredicateCompiler.compilePredicate(
                "tenantCode == $user.tenantId", List.of(), Map.of(), tito).get(0).get(0).value());

        com.npdev.kernel.ExecutionContext anonymous = com.npdev.kernel.ExecutionContext.anonymous();
        assertEquals(PredicateOperator.IS_NULL, ConceptQueryPredicateCompiler.compilePredicate(
                "ownerUsername == $user.id", List.of(), Map.of(), anonymous).get(0).get(0).operator());
        assertEquals(PredicateOperator.IS_NOT_NULL, ConceptQueryPredicateCompiler.compilePredicate(
                "ownerUsername != $user.id", List.of(), Map.of(), anonymous).get(0).get(0).operator());
    }

    @Test
    void userReferenceWithNoCallerIsRefusedNotGuessed() {
        ConceptQueryPredicateCompiler.UnsupportedPredicateException thrown = assertThrows(
                ConceptQueryPredicateCompiler.UnsupportedPredicateException.class,
                () -> ConceptQueryPredicateCompiler.compileToConceptQueryFilters("ownerUsername == $user.id"));
        assertTrue(thrown.getMessage().contains("acting user"), thrown.getMessage());
        List<com.npdev.kernel.concepts.ConceptQuery.Filter> filters =
                ConceptQueryPredicateCompiler.compileToConceptQueryFilters("ownerUsername == $user.id",
                        List.of(), Map.of(), com.npdev.kernel.ExecutionContext.of("t1", "tito"));
        assertEquals(List.of(new com.npdev.kernel.concepts.ConceptQuery.Filter(
                "ownerUsername", com.npdev.kernel.concepts.ConceptQuery.Operator.EQ, "tito")), filters);
    }
}
