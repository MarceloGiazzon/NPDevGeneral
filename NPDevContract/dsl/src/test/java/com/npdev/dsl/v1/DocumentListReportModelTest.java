package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledDocument;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REP-1: {@code documents[].kind: list} (query / columns / groupBy / subtotals / grandTotal) --
 * parse, validate, compile and canonical round trip. A dropped member here is a report that loses its
 * totals on the way to the runtime, silently.
 */
class DocumentListReportModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GOOD = """
            "kind": "list", "query": "OpenSales",
            "columns": [ { "field": "customer", "label": "Customer" }, { "field": "region" },
                         { "field": "amount", "label": "Amount", "total": "sum" } ],
            "groupBy": ["region"], "subtotals": true, "grandTotal": true,
            """;

    @Test
    void aGroupedReportWithSubtotalsValidatesAndCompiles() throws Exception {
        ModelAst ast = parse(model(GOOD));
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "expected no validation errors: " + errors);

        CompiledDocument document = new ModelCompiler().compile(ast).getDocuments().get(0);
        assertTrue(document.listReport());
        assertEquals("OpenSales", document.query());
        assertEquals(3, document.columns().size());
        assertEquals("sum", document.columns().get(2).total());
        assertEquals("Customer", document.columns().get(0).label());
        assertEquals(List.of("region"), document.groupBy());
        assertTrue(document.subtotalsEnabled());
        assertTrue(document.grandTotalEnabled());
    }

    @Test
    void canonicalJsonRoundTripKeepsEveryListMember() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model(GOOD)));
        String json = CompiledModelCanonicalJson.toJson(compiled);

        CompiledDocument restored = CompiledModelCanonicalJsonReader.fromJson(json).getDocuments().get(0);
        assertEquals(compiled.getDocuments().get(0), restored);
        assertEquals(json, CompiledModelCanonicalJson.toJson(CompiledModelCanonicalJsonReader.fromJson(json)),
                "canonical JSON must be a fixed point");
    }

    @Test
    void aOneRecordDocumentWritesNoListMembersSoOlderCanonicalFilesStayByteIdentical() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model("")));
        JsonNode document = MAPPER.readTree(CompiledModelCanonicalJson.toJson(compiled)).get("documents").get(0);
        for (String member : List.of("kind", "query", "columns", "groupBy", "subtotals", "grandTotal")) {
            assertFalse(document.has(member), member + " must be absent for a pre-REP-1 document");
        }
        CompiledDocument restored = CompiledModelCanonicalJsonReader
                .fromJson(CompiledModelCanonicalJson.toJson(compiled)).getDocuments().get(0);
        assertFalse(restored.listReport());
        assertTrue(restored.columns().isEmpty());
    }

    @Test
    void unknownKindIsRejectedBySchema() {
        assertThrows(Exception.class, () -> parse(model("\"kind\": \"matrix\",")));
    }

    @Test
    void listMembersWithoutKindListAreRejected() throws Exception {
        assertError("only apply to a list report", model("\"columns\": [ { \"field\": \"customer\" } ],"));
    }

    @Test
    void aListReportNeedsColumnsOnTheConcept() throws Exception {
        assertError("at least one column", model("\"kind\": \"list\","));
        assertError("column field 'nope' not found on concept 'Sale'",
                model("\"kind\": \"list\", \"columns\": [ { \"field\": \"nope\" } ],"));
        assertError("duplicate column 'customer'",
                model("\"kind\": \"list\", \"columns\": [ { \"field\": \"customer\" }, { \"field\": \"customer\" } ],"));
    }

    @Test
    void groupByAndTotalsAreCheckedBeforeTheyCanPrintAWrongReport() throws Exception {
        String columns = "\"kind\": \"list\", \"columns\": [ { \"field\": \"customer\" }, { \"field\": \"amount\", \"total\": \"sum\" } ],";
        assertError("groupBy field 'nope' not found", model(columns + "\"groupBy\": [\"nope\"],"));
        assertError("subtotals needs groupBy", model(columns + "\"subtotals\": true,"));
        assertError("need at least one column with a 'total'",
                model("\"kind\": \"list\", \"columns\": [ { \"field\": \"customer\" } ], \"grandTotal\": true,"));
        assertError("WITHOUT a 'total'",
                model("\"kind\": \"list\", \"columns\": [ { \"field\": \"amount\", \"total\": \"sum\" } ], \"grandTotal\": true,"));
    }

    @Test
    void theNamedQueryMustExistBePlainAndOverTheSameConcept() throws Exception {
        String columns = "\"kind\": \"list\", \"columns\": [ { \"field\": \"customer\" } ],";
        assertError("query not found: Ghost", model(columns + "\"query\": \"Ghost\","));
        assertError("is an aggregate query", model(columns + "\"query\": \"SalesByRegion\","));
        assertError("reads concept 'Customer' but the document's concept is 'Sale'",
                model(columns + "\"query\": \"CustomerQuery\","));
    }

    @Test
    void aListReportRefusesAggregateBandsAndLogo() throws Exception {
        assertError("does not support aggregate/bands/logo",
                model("\"kind\": \"list\", \"columns\": [ { \"field\": \"customer\" } ], \"logo\": { \"field\": \"customer\" },"));
    }

    // ------------------------------------------------------------------------------------------

    private static void assertError(String fragment, String json) throws Exception {
        List<String> errors = new SemanticValidator().validate(parse(json));
        assertTrue(errors.stream().anyMatch(e -> e.contains(fragment)),
                "expected an error containing '" + fragment + "', got: " + errors);
    }

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static String model(String documentMembers) {
        return """
                {
                  "dslVersion": "1.0.0", "namespace": "rep1.sales", "version": "1.0",
                  "concepts": [
                    { "name": "Sale", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "customer", "type": "string" },
                      { "name": "region", "type": "string" },
                      { "name": "amount", "type": "decimal" }
                    ] },
                    { "name": "Customer", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "name", "type": "string" }
                    ] }
                  ],
                  "queries": [
                    { "name": "OpenSales", "concept": "Sale", "where": "amount > 0", "orderBy": ["customer"] },
                    { "name": "CustomerQuery", "concept": "Customer", "where": "name != ''" },
                    { "name": "SalesByRegion", "concept": "Sale", "groupBy": ["region"],
                      "aggregates": [ { "name": "total", "fn": "sum", "field": "amount" } ] }
                  ],
                  "documents": [
                    {
                      "name": "SalesReport",
                      "concept": "Sale",
                      %s
                      "title": "Sales"
                    }
                  ]
                }
                """.formatted(documentMembers);
    }
}
