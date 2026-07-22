package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies AutoPanel computed-column expressions are syntax-validated at author time (P3 slice 2). */
class ComputedExpressionValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static ValidationResult validateWithWarnings(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validateWithWarnings(ast);
    }

    @Test
    void invalidComputedExpressionIsReported() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.ap", "version": "1.0",
              "concepts": [
                { "name": "Item", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "pos", "type": "integer" } ] }
              ],
              "autoPanels": [
                { "concept": "Item", "surfaces": ["selection"],
                  "selection": { "computed": [{ "col": "total", "expr": "pos * " }] } }
              ]
            }
            """;
        List<String> errors = validate(json);
        assertTrue(errors.stream().anyMatch(e -> e.contains("computed column total") && e.contains("invalid expression")),
                "expected an invalid-expression error, got: " + errors);
    }

    @Test
    void validComputedExpressionPasses() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.ap", "version": "1.0",
              "concepts": [
                { "name": "Item", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "pos", "type": "integer" },
                  { "name": "cxAvulsas", "type": "integer" } ] }
              ],
              "autoPanels": [
                { "concept": "Item", "surfaces": ["selection"],
                  "selection": { "computed": [{ "col": "total", "expr": "pos*42 + cxAvulsas" }] } }
              ]
            }
            """;
        List<String> errors = validate(json);
        assertTrue(errors.stream().noneMatch(e -> e.contains("invalid expression")),
                "no expression error expected, got: " + errors);
    }

    @Test
    void computedWithoutRecomputeProcedureWarnsItStaysMetadataOnly() throws Exception {
        // AW-P3: computed[] compiles into panel metadata but nothing evaluates it client-side --
        // live recompute-on-keystroke is delivered entirely by transaction.metadata.recompute. Warn
        // (don't block) so a declared computed[] never silently does nothing.
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.ap", "version": "1.0",
              "concepts": [
                { "name": "Item", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "pos", "type": "integer" },
                  { "name": "cxAvulsas", "type": "integer" } ] }
              ],
              "autoPanels": [
                { "concept": "Item", "surfaces": ["transaction"],
                  "transaction": { "computed": [{ "col": "total", "expr": "pos*42 + cxAvulsas" }] } }
              ]
            }
            """;
        ValidationResult result = validateWithWarnings(json);
        assertFalse(result.hasErrors(), "computed[] without recompute must warn, not block: " + result.getErrors());
        assertTrue(result.getWarnings().stream().anyMatch(w ->
                        w.contains("computed[]") && w.contains("recompute") && w.contains("metadata only")),
                "expected a computed-without-recompute warning, got: " + result.getWarnings());
    }

    @Test
    void computedWithRecomputeProcedureDoesNotWarn() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.ap", "version": "1.0",
              "concepts": [
                { "name": "Item", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "pos", "type": "integer" },
                  { "name": "cxAvulsas", "type": "integer" } ] }
              ],
              "procedures": [
                { "name": "Recalcular", "steps": [{ "name": "done", "type": "return", "value": "ok" }] }
              ],
              "autoPanels": [
                { "concept": "Item", "surfaces": ["transaction"],
                  "transaction": {
                    "computed": [{ "col": "total", "expr": "pos*42 + cxAvulsas" }],
                    "metadata": { "recompute": "Recalcular" }
                  } }
              ]
            }
            """;
        ValidationResult result = validateWithWarnings(json);
        assertFalse(result.hasErrors(), "unexpected errors: " + result.getErrors());
        assertTrue(result.getWarnings().stream().noneMatch(w -> w.contains("computed[]") && w.contains("metadata only")),
                "no computed-without-recompute warning expected when recompute is declared, got: " + result.getWarnings());
    }
}
