package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledGuidePage;
import com.npdev.dsl.v1.compiled.CompiledGuidePageRegion;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A side region of a back-office guide page can be folded away by the person using the page
 * ({@code collapsible}) and can start folded ({@code defaultCollapsed}). Both keywords sit on a region of the
 * {@code admin.pages[].regions} block of the {@code admin} experience; they are silently lost if the parser or the
 * compiler forgets them, and nothing else in the unit tests names them.
 */
class GuidePageRegionCollapseSurvivesCompileTest {

    private static CompiledGuidePage compiledPage(Path tempDir, String name) throws Exception {
        Path modelPath = tempDir.resolve("model.json");
        Files.writeString(modelPath, """
                {
                  "namespace": "guide.collapse.demo",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Widget", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ],
                  "experiences": [ { "name": "admin", "admin": {
                    "pages": [
                      { "name": "Ops",
                        "regions": {
                          "top": true,
                          "left": { "collapsible": true, "defaultCollapsed": true, "width": 240 },
                          "right": { "enabled": true }
                        } }
                    ]
                  } } ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "expected no validation errors, got: " + validation.getErrors());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        return compiled.getGuidePages().stream()
                .filter(page -> page.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void collapsibleAndDefaultCollapsedReachTheCompiledRegion(@TempDir Path tempDir) throws Exception {
        CompiledGuidePageRegion left = compiledPage(tempDir, "Ops").regions().left();

        assertTrue(left.collapsible(), "the left region was declared collapsible");
        assertTrue(left.defaultCollapsed(), "the left region was declared to start folded");
        assertEquals(240, left.width());
    }

    @Test
    void aRegionThatSaysNothingAboutFoldingIsNeitherCollapsibleNorFolded(@TempDir Path tempDir) throws Exception {
        CompiledGuidePageRegion right = compiledPage(tempDir, "Ops").regions().right();

        assertFalse(right.collapsible(), "folding is opt-in: a region that does not say so stays open and fixed");
        assertFalse(right.defaultCollapsed());
    }
}
