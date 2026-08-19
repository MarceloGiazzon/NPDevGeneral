package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.DocumentAst;
import com.npdev.dsl.v1.ast.DocumentBandAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.PanelFieldBindingAst;
import com.npdev.dsl.v1.compiled.CompiledDocument;
import com.npdev.dsl.v1.compiled.CompiledDocumentBand;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5.7 (Roadmap Wave 1 2026-08-19): {@code documents[].aggregate}/{@code bands}/{@code logo} --
 * parse -> compile -> validate coverage. The canonical-JSON writer/reader round trip for the same
 * new fields is covered generically (no edits needed here) by {@link
 * com.npdev.dsl.v1.compiled.CanonicalJsonRoundTripCompletenessTest}'s reflective sweep.
 */
class DocumentBandModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_BANDS = """
            "bands": [
              { "name": "header", "kind": "header", "label": "Order", "fields": [ { "field": "number" } ] },
              { "name": "lines", "kind": "lineItems", "collection": "lines", "label": "Line items",
                "fields": [ { "field": "sku" }, { "field": "qty" } ] }
            ],
            """;

    @Test
    void aggregateBoundDocumentWithHeaderAndLineItemBandsAndLogoValidatesClean() throws Exception {
        ModelAst ast = parse(model("\"aggregate\": \"InvoiceAggregate\",", DEFAULT_BANDS, "\"logo\": { \"field\": \"logoRef\" },"));

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "expected no validation errors: " + errors);

        DocumentAst document = ast.getDocuments().get(0);
        assertEquals("InvoiceAggregate", document.aggregate());
        assertEquals(2, document.bands().size());
        assertEquals("logoRef", document.logo().field());

        DocumentBandAst header = document.bands().get(0);
        assertEquals("header", header.kind());
        assertNull(header.collection());
        assertEquals(1, header.fields().size());
        assertEquals("number", header.fields().get(0).field());

        DocumentBandAst lines = document.bands().get(1);
        assertEquals("lineItems", lines.kind());
        assertEquals("lines", lines.collection());
        assertEquals(List.of("sku", "qty"), lines.fields().stream().map(PanelFieldBindingAst::field).toList());
    }

    @Test
    void compileThreadsAggregateBandsAndLogoIntoTheCompiledDocument() throws Exception {
        ModelAst ast = parse(model("\"aggregate\": \"InvoiceAggregate\",", DEFAULT_BANDS, "\"logo\": { \"field\": \"logoRef\" },"));
        assertTrue(new SemanticValidator().validate(ast).isEmpty());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledDocument document = compiled.getDocuments().stream()
                .filter(d -> d.name().equals("InvoicePdf"))
                .findFirst().orElseThrow();

        assertEquals("InvoiceAggregate", document.aggregate());
        assertEquals("logoRef", document.logo().field());
        assertEquals(2, document.bands().size());

        CompiledDocumentBand lines = document.bands().stream()
                .filter(b -> "lines".equals(b.name())).findFirst().orElseThrow();
        assertEquals("lineItems", lines.kind());
        assertEquals("lines", lines.collection());
        assertEquals(List.of("qty", "sku"), // compilePanelFieldBindings sorts by field name
                lines.fields().stream().map(f -> f.field()).sorted().toList());
    }

    @Test
    void aPreR57FlatConceptDocumentWithNoBandsStillValidatesClean() throws Exception {
        ModelAst ast = parse(model("", "", ""));

        assertTrue(new SemanticValidator().validate(ast).isEmpty());
        DocumentAst document = ast.getDocuments().get(0);
        assertTrue(document.bands().isEmpty());
        assertNull(document.aggregate());
        assertNull(document.logo());
    }

    /**
     * R5.7's "watch for": a band with zero field bindings must never render as a silent blank
     * table. Two independent gates enforce this -- schema ({@code documentBand.fields} declares
     * {@code minItems: 1}, so an empty band never even parses) and {@link
     * com.npdev.dsl.v1.validation.DocumentValidation} (a defense-in-depth check for a {@link
     * ModelAst} assembled without going through JSON schema validation, e.g. programmatically). This
     * test proves the FIRST, earliest gate: parsing itself refuses the model outright.
     */
    @Test
    void anEmptyBandFailsSchemaValidationAtParseTimeRatherThanSilentlyParsing() {
        String bands = """
                "bands": [
                  { "name": "header", "kind": "header", "fields": [ { "field": "number" } ] },
                  { "name": "lines", "kind": "lineItems", "collection": "lines", "fields": [] }
                ],
                """;
        String json = model("\"aggregate\": \"InvoiceAggregate\",", bands, "");

        Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class, () -> parse(json));
        assertTrue(thrown.getMessage() != null && thrown.getMessage().toLowerCase().contains("fields"),
                "expected the schema violation to name 'fields', got: " + thrown.getMessage());
    }

    @Test
    void aLineItemsBandNamingAnUnknownCollectionIsRejected() throws Exception {
        String bands = """
                "bands": [
                  { "name": "lines", "kind": "lineItems", "collection": "doesNotExist",
                    "fields": [ { "field": "sku" } ] }
                ],
                """;
        ModelAst ast = parse(model("\"aggregate\": \"InvoiceAggregate\",", bands, ""));

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.stream().anyMatch(e -> e.contains("collection not found") && e.contains("doesNotExist")),
                "expected an unknown-collection error, got: " + errors);
    }

    @Test
    void bandsWithoutADeclaredAggregateAreRejected() throws Exception {
        String bands = """
                "bands": [
                  { "name": "header", "kind": "header", "fields": [ { "field": "number" } ] }
                ],
                """;
        ModelAst ast = parse(model("", bands, ""));

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no aggregate is bound")),
                "expected a missing-aggregate error, got: " + errors);
    }

    @Test
    void aLogoFieldNotFoundOnTheRootConceptIsRejected() throws Exception {
        ModelAst ast = parse(model("\"aggregate\": \"InvoiceAggregate\",", DEFAULT_BANDS,
                "\"logo\": { \"field\": \"noSuchField\" },"));

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.stream().anyMatch(e -> e.contains("logo field") && e.contains("noSuchField")),
                "expected a logo-field-not-found error, got: " + errors);
    }

    @Test
    void aHeaderBandFieldNotFoundOnTheConceptIsRejected() throws Exception {
        String bands = """
                "bands": [
                  { "name": "header", "kind": "header", "fields": [ { "field": "doesNotExist" } ] }
                ],
                """;
        ModelAst ast = parse(model("\"aggregate\": \"InvoiceAggregate\",", bands, ""));

        List<String> errors = new SemanticValidator().validate(ast);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("doesNotExist") && e.contains("not found on concept")),
                "expected a field-not-found error, got: " + errors);
    }

    // ------------------------------------------------------------------------------------------

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static String model(String aggregateLine, String bandsBlock, String logoLine) {
        return """
                {
                  "dslVersion": "1.0.0", "namespace": "r57.invoicing", "version": "1.0",
                  "concepts": [
                    { "name": "Invoice", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "number", "type": "string" },
                      { "name": "logoRef", "type": "string" }
                    ] },
                    { "name": "InvoiceLine", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "invoiceId", "type": "uuid" },
                      { "name": "sku", "type": "string" },
                      { "name": "qty", "type": "integer" }
                    ] }
                  ],
                  "aggregates": [
                    { "name": "InvoiceAggregate", "root": "Invoice", "collections": [
                      { "name": "lines", "concept": "InvoiceLine", "childField": "invoiceId", "ownership": "owned" }
                    ] }
                  ],
                  "documents": [
                    {
                      "name": "InvoicePdf",
                      "concept": "Invoice",
                      "title": "Invoice",
                      %s
                      %s
                      %s
                      "metadata": {}
                    }
                  ]
                }
                """.formatted(aggregateLine, logoLine, bandsBlock);
    }
}
