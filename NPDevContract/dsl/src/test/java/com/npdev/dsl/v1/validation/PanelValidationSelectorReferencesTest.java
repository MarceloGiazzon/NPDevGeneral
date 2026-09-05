package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REAL_LIFT_PLAN_2026-09-03 package C2 (boundary B16 Step 2, EDIT-18): {@code
 * PanelValidation.validateSelectorReferences} -- a {@code selectorRef} naming an undeclared
 * selector is an ERROR (mirrors {@code PropertyValidation}'s {@code settableAt} existence-check
 * pattern), and a {@code selectors[]} entry referenced by nothing is a WARNING, not an error.
 */
class PanelValidationSelectorReferencesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelAst parse(String selectorsBlock, String pickerBlock) throws Exception {
        String json = """
                {
                  "dslVersion": "1.0.0", "namespace": "selector.refs", "version": "1.0",
                  "concepts": [
                    { "name": "Widget", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "widgetRef", "type": "uuid"
                          %s
                        }
                    ] }
                  ]
                  %s
                }
                """.formatted(pickerBlock, selectorsBlock);
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    @Test
    void aSelectorRefNamingARealDeclaredSelectorValidatesClean() throws Exception {
        ModelAst ast = parse(
                """
                , "selectors": [
                  { "name": "WidgetPicker", "concept": "Widget", "columns": ["id"] }
                ]
                """,
                ", \"picker\": { \"selectorRef\": \"WidgetPicker\" }");

        ValidationResult result = new SemanticValidator().validateWithWarnings(ast);

        assertTrue(result.getErrors().isEmpty(), "expected no errors: " + result.getErrors());
        assertTrue(result.getWarnings().stream().noneMatch(w -> w.contains("WidgetPicker")),
                "the referenced selector should not be flagged as unreferenced: " + result.getWarnings());
    }

    @Test
    void aSelectorRefNamingAnUndeclaredSelectorIsARealError() throws Exception {
        ModelAst ast = parse("", ", \"picker\": { \"selectorRef\": \"NoSuchSelector\" }");

        ValidationResult result = new SemanticValidator().validateWithWarnings(ast);

        assertFalse(result.getErrors().isEmpty(), "expected an error for the undeclared selectorRef");
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("NoSuchSelector")),
                "expected the real undeclared name in the error: " + result.getErrors());
    }

    @Test
    void aDeclaredSelectorReferencedByNothingIsAWarningNotAnError() throws Exception {
        ModelAst ast = parse(
                """
                , "selectors": [
                  { "name": "UnusedPicker", "concept": "Widget", "columns": ["id"] }
                ]
                """,
                "");

        ValidationResult result = new SemanticValidator().validateWithWarnings(ast);

        assertTrue(result.getErrors().isEmpty(),
                "an unreferenced selector must not be an error: " + result.getErrors());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("UnusedPicker")),
                "expected a warning naming the unreferenced selector: " + result.getWarnings());
    }
}
