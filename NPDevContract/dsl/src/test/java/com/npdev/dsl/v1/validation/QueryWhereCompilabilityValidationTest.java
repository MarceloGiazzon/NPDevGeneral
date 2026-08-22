package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 12 P1.4 (item 2 / REG-101, fix shape (c)): {@code queries[].where} is now refused at
 * AUTHORING time when it cannot compile under the grammar the kernel enforces at runtime -- the
 * durable fix the ledger item asked for, replacing {@code check-query-predicate-compilable.py}'s
 * Python reimplementation of the same grammar (now deleted). Goes through the real
 * {@link JsonModelParser} + {@link SemanticValidator} front door, per the REG-89 lesson.
 *
 * <p>Reproduces REG-101's own corpus witness, {@code pack-sample}'s {@code SalesByStore}
 * ({@code where: "storeId == :storeId"}, {@code parameters: [{name: storeId}]}) as the positive
 * case, and its two negative siblings: a placeholder naming an undeclared parameter, and a
 * predicate the grammar cannot parse at all.
 */
class QueryWhereCompilabilityValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String whereClause, String parametersJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.qwhere", "version": "1.0",
              "concepts": [
                { "name": "Sale", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "storeId", "type": "string" },
                  { "name": "amount", "type": "integer" } ] }
              ],
              "queries": [
                { "name": "SalesByStore", "concept": "Sale", "where": "%s", "parameters": [ %s ] }
              ]
            }
            """.formatted(whereClause, parametersJson);
    }

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static final String STORE_ID_PARAMETER =
            "{ \"name\": \"storeId\", \"type\": \"uuid\", \"required\": true }";

    @Test
    void aBindPlaceholderMatchingADeclaredParameterCompilesClean() throws Exception {
        List<String> errors = validate(modelJson("storeId == :storeId", STORE_ID_PARAMETER));
        assertTrue(errors.stream().noneMatch(e -> e.contains("where")), "unexpected: " + errors);
    }

    @Test
    void aBindPlaceholderWithNoDeclaredParameterIsRejected() throws Exception {
        List<String> errors = validate(modelJson("storeId == :storeId", ""));
        assertTrue(errors.stream().anyMatch(e ->
                        e.contains("bind placeholder :storeId") && e.contains("not declared in this query's parameters[]")),
                "a placeholder naming an undeclared parameter must be refused at authoring time, not left "
                        + "to silently compare every row against the literal \":storeId\" (REG-101): " + errors);
    }

    /**
     * R4.3 lockstep fix: {@code "storeId in ('a','b')"} used to be this test's "genuinely
     * uncompilable" example -- true only while {@code validateQueryWhereCompiles} was still on the
     * v1-only grammar (no {@code in} operator). Now that it accepts the v2 grammar (see that
     * method's own javadoc), {@code in} compiles cleanly -- {@link #anInClauseNowCompilesCleanUnderV2}
     * proves that positive case. This test keeps the SAME intent (a predicate genuinely outside
     * every supported grammar) with an example neither grammar version can ever parse: an
     * unsupported comparison operator.
     */
    @Test
    void aGenuinelyUncompilablePredicateIsRejected() throws Exception {
        List<String> errors = validate(modelJson("storeId >< 'x'", STORE_ID_PARAMETER));
        assertTrue(errors.stream().anyMatch(e -> e.contains("where cannot be compiled")), "expected: " + errors);
    }

    /** R4.3 lockstep fix: the positive sibling of the rewritten test above -- {@code in} is now
     *  part of the accepted grammar, matching what {@code DefaultProcedureExecutor}'s {@code runQuery}
     *  step can now actually execute (compileToConceptQueryFilters). */
    @Test
    void anInClauseNowCompilesCleanUnderV2() throws Exception {
        List<String> errors = validate(modelJson("storeId in ('a','b')", ""));
        assertTrue(errors.stream().noneMatch(e -> e.contains("where")), "unexpected: " + errors);
    }

    /** R4.3 lockstep fix: an OR-group, a reference-path join, and a date comparison together --
     *  exactly the shape the runtime already proves against real H2
     *  ({@code JdbcBusinessConceptStorePredicateV2Test}). */
    @Test
    void anOrGroupWithAReferencePathJoinAndADateComparisonCompilesClean() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.qwhere.v2", "version": "1.0",
              "concepts": [
                { "name": "Store", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "country", "type": "string" } ] },
                { "name": "Sale", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "storeId", "type": "reference", "reference": { "target": "Store" } },
                  { "name": "soldOn", "type": "date" },
                  { "name": "amount", "type": "integer" } ] }
              ],
              "queries": [
                { "name": "SalesV2", "concept": "Sale",
                  "where": "storeId.country == 'US' && soldOn < '2026-02-01' || amount >= 1000" }
              ]
            }
            """;
        List<String> errors = validate(json);
        assertTrue(errors.stream().noneMatch(e -> e.contains("where")), "unexpected: " + errors);
    }

    /** R4.3 lockstep fix: a predicate join into a concept declaring {@code access.read} is refused --
     *  the same C3 information-disclosure rationale {@code validateGroupByField} already enforces for
     *  {@code groupBy}, extended to {@code where} since there is no runtime backstop for this shape
     *  (unlike {@code groupBy}'s {@code DefaultConceptGateway#aggregate} hard stop). */
    @Test
    void aPredicateJoinIntoAnAccessReadRestrictedConceptIsRejected() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.qwhere.v2secure", "version": "1.0",
              "concepts": [
                { "name": "Store", "access": { "read": "true" }, "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "country", "type": "string" } ] },
                { "name": "Sale", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "storeId", "type": "reference", "reference": { "target": "Store" } },
                  { "name": "amount", "type": "integer" } ] }
              ],
              "queries": [
                { "name": "SalesByStoreCountry", "concept": "Sale", "where": "storeId.country == 'US'" }
              ]
            }
            """;
        List<String> errors = validate(json);
        assertTrue(errors.stream().anyMatch(e ->
                        e.contains("where join") && e.contains("Store") && e.contains("access.read")),
                "a predicate join into an access.read-restricted concept must be refused -- no runtime "
                        + "check exists for this shape: " + errors);
    }

    @Test
    void anOrdinaryLiteralWhereWithNoPlaceholderStillCompilesClean() throws Exception {
        List<String> errors = validate(modelJson("storeId == 'store-a'", ""));
        assertTrue(errors.stream().noneMatch(e -> e.contains("where")), "unexpected: " + errors);
    }
}
