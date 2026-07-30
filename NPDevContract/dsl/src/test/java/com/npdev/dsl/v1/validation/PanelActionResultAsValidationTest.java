package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 4 / Gap 7): a panelAction's {@code resultAs:
 * "download"} is only meaningful on a {@code procedure}-binding action and needs both {@code
 * filename} and {@code contentType} declared alongside it.
 */
class PanelActionResultAsValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String actionJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.qdownload", "version": "1.0",
              "concepts": [
                { "name": "Widget", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "procedures": [
                { "name": "ExportProcedure", "steps": [
                    { "name": "echo", "type": "mapValue", "value": "$input", "target": "out" },
                    { "name": "ret", "type": "return", "value": "$out" } ] }
              ],
              "panels": [
                { "name": "TestPanel", "route": "/test-panel",
                  "dataSources": [ { "name": "widgets", "concept": "Widget" } ],
                  "actions": [ %s ] }
              ]
            }
            """.formatted(actionJson);
    }

    private static List<String> validate(String actionJson) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(modelJson(actionJson)));
        return new SemanticValidator().validate(ast);
    }

    @Test
    void wellFormedDownloadActionPasses() throws Exception {
        List<String> errors = validate("""
            { "name": "export", "label": "Export", "binding": "procedure", "procedure": "ExportProcedure",
              "resultAs": "download", "filename": "export.csv", "contentType": "text/csv" }
            """);
        assertTrue(errors.stream().noneMatch(e -> e.contains("resultAs")), "unexpected errors: " + errors);
    }

    @Test
    void resultAsOnANonProcedureBindingIsRejected() throws Exception {
        List<String> errors = validate("""
            { "name": "export", "label": "Export", "binding": "conceptQuery", "concept": "Widget",
              "resultAs": "download", "filename": "export.csv", "contentType": "text/csv" }
            """);
        assertTrue(errors.stream().anyMatch(e -> e.contains("resultAs is only supported on a procedure-binding action")),
                "expected a binding-mismatch error, got: " + errors);
    }

    @Test
    void missingFilenameIsRejected() throws Exception {
        List<String> errors = validate("""
            { "name": "export", "label": "Export", "binding": "procedure", "procedure": "ExportProcedure",
              "resultAs": "download", "contentType": "text/csv" }
            """);
        assertTrue(errors.stream().anyMatch(e -> e.contains("requires filename")),
                "expected a missing-filename error, got: " + errors);
    }

    @Test
    void missingContentTypeIsRejected() throws Exception {
        List<String> errors = validate("""
            { "name": "export", "label": "Export", "binding": "procedure", "procedure": "ExportProcedure",
              "resultAs": "download", "filename": "export.csv" }
            """);
        assertTrue(errors.stream().anyMatch(e -> e.contains("requires contentType")),
                "expected a missing-contentType error, got: " + errors);
    }

    @Test
    void anActionWithNoResultAsDeclaredNeedsNeitherFilenameNorContentType() throws Exception {
        List<String> errors = validate("""
            { "name": "export", "label": "Export", "binding": "procedure", "procedure": "ExportProcedure" }
            """);
        assertTrue(errors.stream().noneMatch(e -> e.contains("resultAs") || e.contains("filename") || e.contains("contentType")),
                "unexpected errors: " + errors);
    }
}
