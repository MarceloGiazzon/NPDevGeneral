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
                        "compileToConceptQueryFilters(query.where(),query.parameters(),bound)"),
                "runPlain must compile v2 filters with declared-parameter binding:\n" + source);
    }

    @Test
    void aggregateAndHavingAlsoUseV2CompilerWithBoundParameters() {
        String source = compact(renderedController());
        assertTrue(source.contains(
                        "compileToConceptQueryFilters(query.where(),query.parameters(),bound)"),
                "runAggregate where must bind v2 parameters:\n" + source);
        assertTrue(source.contains(
                        "compileToConceptQueryFilters(query.having(),query.parameters(),bound)"),
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
        assertTrue(source.contains("request.getParameter(parameter.name())"),
                "declared parameters must bind from request query-string values");
        assertTrue(source.contains("is required (declared in this"),
                "an unbound declared parameter must be refused (X0), never dropped from the filter");
        assertTrue(source.contains("HttpStatus.BAD_REQUEST, predicate.getMessage()"),
                "an unsupported predicate must surface as 400, not 500");
    }

    @Test
    void valuesAreCoercedByShapeForColumnTypedFilters() {
        String source = renderedController();
        assertTrue(source.contains("private static Object coerceLiteral(String raw)"),
                "a literal coercion helper must exist");
        assertTrue(source.contains("Long.parseLong(trimmed)") && source.contains("Double.parseDouble(trimmed)"),
                "numeric query-string values must coerce to their numeric types so the filter literal "
                        + "matches the stored column type");
        assertTrue(source.contains("\"true\".equalsIgnoreCase(trimmed)"),
                "boolean query-string values must coerce to Boolean");
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