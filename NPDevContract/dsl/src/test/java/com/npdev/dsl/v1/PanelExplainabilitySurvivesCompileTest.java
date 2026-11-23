package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelAction;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A screen panel and each of its buttons may carry an {@code explainability} object: free-form notes saying why the
 * panel or the action exists, read later by AI and audit tools. The notes are the only reason such a tool can answer
 * "why is this button here", so they must reach the compiled model unchanged; nothing else in the unit tests names
 * the keyword.
 */
class PanelExplainabilitySurvivesCompileTest {

    @Test
    void panelAndActionExplainabilityReachTheCompiledPanel(@TempDir Path tempDir) throws Exception {
        Path modelPath = tempDir.resolve("model.json");
        Files.writeString(modelPath, """
                {
                  "namespace": "panel.explain.demo",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Widget", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "label", "type": "string" } ] }
                  ],
                  "flows": [
                    { "name": "ApproveWidget", "concept": "Widget", "steps": [
                      { "name": "done", "type": "return", "value": "$input" }
                    ] }
                  ],
                  "screens": [
                    { "name": "WidgetBoard", "kind": "custom", "panel": {
                      "route": "/widgets",
                      "explainability": { "why": "gives the floor team one place to review widgets" },
                      "dataSources": [ { "name": "rows", "concept": "Widget" } ],
                      "layout": { "type": "table", "fields": ["label"] },
                      "actions": [
                        { "name": "approve", "label": "Approve", "binding": "flow", "flow": "ApproveWidget",
                          "scope": "row", "dataSource": "rows",
                          "explainability": { "why": "an approved widget may be shipped" } }
                      ] } }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "expected no validation errors, got: " + validation.getErrors());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledPanel panel = compiled.getPanels().stream()
                .filter(candidate -> candidate.name().equals("WidgetBoard"))
                .findFirst()
                .orElseThrow();
        assertEquals("gives the floor team one place to review widgets", panel.explainability().get("why"));

        CompiledPanelAction approve = panel.actions().stream()
                .filter(action -> action.name().equals("approve"))
                .findFirst()
                .orElseThrow();
        assertEquals("an approved widget may be shipped", approve.explainability().get("why"));
    }
}
