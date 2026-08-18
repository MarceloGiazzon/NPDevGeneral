package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-185 at the level that matters to an author: does {@code npdev validate model} FAIL?
 *
 * <p>{@code ReferenceIndexTest} proves the index classifies references correctly. This proves the
 * classification actually reaches a diagnostic -- the two are separable, and the version of this
 * work that computed a perfect index and wired it to nothing would have passed the other test in
 * full.
 *
 * <p>The measured RED for every case below is the same sentence: before this,
 * {@code npdev validate model} reported {@code status: passed, errors: 0, warnings: 0}.
 */
class ReferenceIntegrityValidationTest {

    private static ValidationResult validate(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-reg185-", ".json");
        Files.writeString(modelPath, json);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        return new SemanticValidator().validateWithWarnings(ast);
    }

    private static String modelWith(String panelExtras, String queryExtras) {
        return """
                {
                  "namespace": "reg185.sample",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "WidgetOrder", "ui": { "label": "Widget order" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "customerEmail", "type": "string", "ui": { "label": "Email" } },
                      { "name": "status", "type": "string", "ui": { "label": "Status" } }
                    ] }
                  ],
                  "queries": [ { "name": "AllOrders", "concept": "WidgetOrder" }%s ],
                  "panels": [
                    {
                      "name": "OrdersPanel",
                      "route": "/orders",
                      "dataSources": [ { "name": "orders", "concept": "WidgetOrder" } ],
                      "layout": { "type": "table", "fields": ["customerEmail"] }%s
                    }
                  ]
                }
                """.formatted(queryExtras, panelExtras);
    }

    private static void assertOneError(ValidationResult result, String expectedMessage) {
        assertEquals(List.of(expectedMessage), result.getErrors(),
                "expected exactly one reference error");
    }

    @Test
    void aCleanModelStillPasses() throws Exception {
        // The control. A checker that fails everything catches every bug and is worthless.
        assertEquals(List.of(), validate(modelWith("", "")).getErrors());
    }

    @Test
    void panelLayoutFieldsGhostIsAnError() throws Exception {
        String json = modelWith("", "").replace(
                "\"fields\": [\"customerEmail\"]",
                "\"fields\": [\"customerEmail\", \"totallyMadeUpField\"]");

        assertOneError(validate(json),
                "Panel OrdersPanel layout.fields: references unknown field totallyMadeUpField "
                        + "on concept WidgetOrder");
    }

    @Test
    void panelFieldBindingGhostIsAnError() throws Exception {
        String json = modelWith("""
                ,
                      "fieldBindings": [
                        { "field": "customerEmail", "source": "orders", "editable": true },
                        { "field": "anotherGhostField", "source": "orders", "editable": true }
                      ]""", "");

        assertOneError(validate(json),
                "Panel OrdersPanel fieldBindings.field: references unknown field anotherGhostField "
                        + "on concept WidgetOrder");
    }

    @Test
    void queryOrderByGhostIsAnErrorBecauseItReachesSql() throws Exception {
        String json = modelWith("", """
                ,
                    { "name": "GhostQuery", "concept": "WidgetOrder", "orderBy": ["ghostOrderField"] }""");

        assertOneError(validate(json),
                "Query GhostQuery orderBy: references unknown field ghostOrderField "
                        + "on concept WidgetOrder");
    }

    @Test
    void queryWhereGhostIsAnError() throws Exception {
        String json = modelWith("", """
                ,
                    { "name": "GhostQuery", "concept": "WidgetOrder", "where": "ghostWhereField == 'A'" }""");

        assertOneError(validate(json),
                "Query GhostQuery where: references unknown field ghostWhereField "
                        + "on concept WidgetOrder");
    }

    @Test
    void fieldBindingPredicateGhostIsAnError() throws Exception {
        String json = modelWith("""
                ,
                      "fieldBindings": [
                        { "field": "customerEmail", "source": "orders", "editable": true,
                          "visibleWhen": "ghostPredicateField == 'A'" }
                      ]""", "");

        assertOneError(validate(json),
                "Panel OrdersPanel fieldBindings.predicate: references unknown field "
                        + "ghostPredicateField on concept WidgetOrder");
    }

    @Test
    void aSiteAnotherValidatorAlreadyCoversIsNotReportedTwice() throws Exception {
        // `addFormFields` has been an error since PanelValidation.java:684. If this class reported
        // it too, one mistake would produce two differently-worded errors, which reads to an author
        // as two mistakes -- the reason REPORTED_ELSEWHERE exists. Written out in full rather than
        // patched into the shared fixture: the first draft chained two string replaces, one silently
        // did not match, and the test then passed a model with no defect in it at all.
        List<String> errors = validate("""
                {
                  "namespace": "reg185.duplicate",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "WidgetOrder", "ui": { "label": "Widget order" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "customerEmail", "type": "string", "ui": { "label": "Email" } }
                    ] }
                  ],
                  "panels": [
                    {
                      "name": "OrdersPanel",
                      "route": "/orders",
                      "dataSources": [
                        { "name": "orders", "concept": "WidgetOrder",
                          "addFormFields": ["ghostFormField"] }
                      ],
                      "layout": { "type": "table", "fields": ["customerEmail"] }
                    }
                  ]
                }
                """).getErrors();

        assertEquals(1, errors.size(), "exactly one message for one mistake, got: " + errors);
        assertTrue(errors.get(0).contains("ghostFormField"), errors.get(0));
    }
}
