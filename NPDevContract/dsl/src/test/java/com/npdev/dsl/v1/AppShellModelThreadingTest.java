package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledAppShell;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path A P6.3: proves {@code appShell} (declared navigation structure + default route for the
 * generated app's shell chrome) survives the real pipeline -- {@link JsonModelParser} ->
 * {@link SemanticValidator} -> {@link ModelCompiler#compile}, which itself always resolves through
 * {@code ModelResolver} first -- not a hand-built {@link com.npdev.dsl.v1.ast.AppShellAst}. The
 * Compiled* writer/reader half of the chain (CompiledModelCanonicalJson/Reader) is covered
 * separately and automatically by CanonicalJsonRoundTripCompletenessTest's reflective ratchet.
 */
class AppShellModelThreadingTest {

    @Test
    void appShellSurvivesParseValidateAndCompile(@TempDir Path tempDir) throws Exception {
        Path modelPath = tempDir.resolve("model.json");
        Files.writeString(modelPath, """
                {
                  "namespace": "appshell.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Widget", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] },
                    { "name": "Gadget", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ],
                  "appShell": {
                    "defaultRoute": "Widget",
                    "navigation": [
                      { "label": "Widgets", "target": "Widget", "group": "Operations" },
                      { "label": "Gadgets", "target": "Gadget", "group": "Operations" },
                      { "label": "Reference Data" }
                    ]
                  }
                }
                """);

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(modelPath);

        assertEquals("Widget", ast.getAppShell().getDefaultRoute());
        assertEquals(3, ast.getAppShell().getNavigation().size());
        assertEquals("Widgets", ast.getAppShell().getNavigation().get(0).getLabel());
        assertEquals("Widget", ast.getAppShell().getNavigation().get(0).getTarget());
        assertEquals("Operations", ast.getAppShell().getNavigation().get(0).getGroup());
        assertNull(ast.getAppShell().getNavigation().get(2).getTarget(),
                "a group-header-only entry declares no target");

        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "expected no validation errors, got: " + validation.getErrors());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledAppShell appShell = compiled.getAppShell();
        assertEquals("Widget", appShell.getDefaultRoute());
        assertEquals(3, appShell.getNavigation().size());
        assertEquals("Gadgets", appShell.getNavigation().get(1).getLabel());
        assertEquals("Gadget", appShell.getNavigation().get(1).getTarget());
        assertEquals("Operations", appShell.getNavigation().get(1).getGroup());
        assertNull(appShell.getNavigation().get(2).getTarget());
    }

    @Test
    void aModelWithNoAppShellBlockCompilesWithNullAppShell_backwardCompatibility(@TempDir Path tempDir) throws Exception {
        Path modelPath = tempDir.resolve("model.json");
        Files.writeString(modelPath, """
                {
                  "namespace": "appshell.none.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Widget", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(modelPath);
        assertNull(ast.getAppShell());

        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "expected no validation errors, got: " + validation.getErrors());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertNull(compiled.getAppShell(), "no default appShell is synthesized -- unlike settings, there is nothing to default to");
    }
}
