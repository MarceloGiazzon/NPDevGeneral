package com.npdev.generator;

import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WMS-12 (QueryExecutionEndpoint gaps): pins the contract of the emitted
 * {@code GET /api/queries/{queryName}} controller. The endpoint existed (Move 10 B1) but used the
 * v1-only {@code ConceptQueryPredicateCompiler.compile(where)} -- a v2 predicate (or/in/contains)
 * threw UnsupportedPredicateException -> 500 -- had no {@code :param} binding and no pagination.
 * This test locks the upgraded template: v2 grammar with bound parameters on the plain AND
 * aggregate paths, X0 unbound-parameter refusal as 400, request-driven offset/limit, and the
 * response carrying offset/limit. Rendered with the same {@link TemplateEngine} the emitters use.
 */
class GeneratedQueryControllerTemplateContractTest {

    private static final String PACKAGE = "com.npdev.generated.controllers";

    private static String renderedController() {
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        return templates.render("business-ui-query-controller.mustache", Map.of(
                "controllerPackage", PACKAGE));
    }

    /** Whitespace-insensitive anchor for multiline call sites (indentation is a render detail). */
    private static String compact(String source) {
        return source.replaceAll("\\s+", "");
    }

    @Test
    void plainPathUsesV2CompilerWithBoundParameters() {
        String source = compact(renderedController());
        assertTrue(source.contains(
                        "compileToConceptQueryFilters(query.where(),query.parameters(),bound,context)"),
                "runPlain must compile v2 filters with declared-parameter binding:\n" + source);
    }

    @Test
    void aggregatePathHonoursTheRequestLimitLikeThePlainPath() {
        // LOAD-4: ?limit= was ignored for grouped queries, so a 20,000-group query shipped all 20,000
        // rows (1.5 MB) for limit=50. Both the store-level and the enriched (lookups/computed) limit
        // must be the request-aware one, never the declared query.limit() alone.
        String source = compact(renderedController());
        assertTrue(source.contains("runAggregate(query,context,bound,request)"),
                "the aggregate path must receive the request so it can read ?limit=:\n" + source);
        assertTrue(source.contains("newConceptAggregateQuery(filters,groupBy,aggregates,having,sorts,effectiveLimit)"),
                "the store-level aggregate must be limited by the request-aware limit");
        assertTrue(source.contains("applyHavingSortAndLimit(rows,having,sorts,effectiveLimit)"),
                "the enriched aggregate must be limited by the request-aware limit");
        assertFalse(source.contains("sorts,query.limit()"),
                "no aggregate path may fall back to the declared limit alone");
    }

    @Test
    void aggregateAndHavingAlsoUseV2CompilerWithBoundParameters() {
        String source = compact(renderedController());
        assertTrue(source.contains(
                        "compileToConceptQueryFilters(query.where(),query.parameters(),bound,context)"),
                "runAggregate where must bind v2 parameters:\n" + source);
        assertTrue(source.contains(
                        "compileToConceptQueryFilters(query.having(),query.parameters(),bound,context)"),
                "runAggregate having must bind v2 parameters:\n" + source);
    }

    @Test
    void oldV1OnlyFlatCompileCallIsGone() {
        assertFalse(renderedController().contains("ConceptQueryPredicateCompiler.compile(query.where())"),
                "the v1-only flat compile(where) call must not survive in the emitted controller");
        assertFalse(renderedController().contains("ConceptQueryPredicateCompiler.compile(query.having())"),
                "the v1-only flat compile(having) call must not survive in the emitted controller");
    }

    @Test
    void boundParametersAreCollectedFromTheRequestAndX0RefusesUnbound() {
        String source = renderedController();
        assertTrue(source.contains("private static Map<String, Object> bindParameters("),
                "a bindParameters helper must exist");
        // REP-1: the binding rule moved verbatim to com.finalexec.api.NamedQueryBinding (shared with the
        // list-report document); its own unit test (NamedQueryBindingTest in runtimehost-core) pins the
        // X0 refusal text, so here the contract is that the controller delegates and maps it to 400.
        assertTrue(source.contains("NamedQueryBinding.bind(request::getParameter, parameters)"),
                "declared parameters must bind from request query-string values through NamedQueryBinding");
        assertTrue(source.contains("catch (IllegalArgumentException unbound)")
                        && source.contains("HttpStatus.BAD_REQUEST, unbound.getMessage()"),
                "an unbound declared parameter must be refused (X0) as a 400, never dropped from the filter");
        assertTrue(source.contains("HttpStatus.BAD_REQUEST, predicate.getMessage()"),
                "an unsupported predicate must surface as 400, not 500");
    }

    @Test
    void valuesAreCoercedByShapeForColumnTypedFilters() {
        String source = renderedController();
        // REP-1: coercion lives in com.finalexec.api.NamedQueryBinding.coerceLiteral (pinned by
        // NamedQueryBindingTest: boolean / long / double / raw string); the controller must go through it.
        assertTrue(source.contains("import com.finalexec.api.NamedQueryBinding;"),
                "the controller must use the shared NamedQueryBinding for literal coercion");
        assertFalse(source.contains("private static Object coerceLiteral(String raw)"),
                "the coercion rule must live in ONE place (NamedQueryBinding), not be re-declared here");
    }

    @Test
    void paginationParamsAreParsedAndEchoed() {
        String source = renderedController();
        assertTrue(source.contains("private static int parseOffset(HttpServletRequest request)"),
                "an offset parser must exist");
        assertTrue(source.contains("private static int parseLimit(HttpServletRequest request, int declaredLimit)"),
                "a limit parser must exist");
        assertTrue(source.contains("ConceptQuery.MAX_LIMIT"),
                "the request limit must be capped at the platform maximum");
        assertTrue(source.contains("int offset = parseOffset(request);")
                        && source.contains("int limit = parseLimit(request, declaredLimit);"),
                "runPlain must apply request-driven pagination");
        assertTrue(source.contains("body.put(\"offset\", offset);") && source.contains("body.put(\"limit\", limit);"),
                "the response must echo offset/limit so a paged client can thread the cursor");
    }
}