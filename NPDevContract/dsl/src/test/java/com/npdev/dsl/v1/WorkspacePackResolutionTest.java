package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the canonical built-in workspace pack ({@code NPDevContract/packs/workspace/pack.json})
 * parses, validates, and compiles. It loads the real artifact (not an inline copy) so the shipped
 * pack is actually exercised.
 */
class WorkspacePackResolutionTest {

    @TempDir
    Path temp;

    @Test
    void builtInWorkspacePackCompiles() throws Exception {
        Path realPack = Path.of("..", "packs", "workspace", "pack.json").toAbsolutePath().normalize();
        assertTrue(Files.exists(realPack), "Built-in workspace pack must exist at " + realPack);

        write("packs/workspace/pack.json", Files.readString(realPack));
        Path model = write("model.json", """
                {
                  "namespace": "workspace.pack.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/workspace/pack.json", "as": "ws" }
                  ],
                  "concepts": [
                    { "name": "Workspace", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ]
                }
                """);

        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(model);
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected workspace pack model to validate, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledConcept menu = compiled.findConcept("ws::Menu").orElseThrow();
        // RC-A2 (Move 14 Phase B item B1): Preference was retired in favor of PropertyValue -- the
        // scoped-property cascade's storage (row presence is the is-set signal; see BREAKING.md's
        // 2026-08-02 entry). This test's own retired assertions (userId/prefKey/prefValue) were
        // missed by B1's own verification pass and only caught here, the first time :dsl:test's full
        // suite ran after that rename -- a real regression, not a pre-existing failure.
        CompiledConcept propertyValue = compiled.findConcept("ws::PropertyValue").orElseThrow();

        CompiledField kind = field(menu, "kind");
        assertTrue(kind.getEnumValues().contains("INTERNAL"), "Menu.kind should offer INTERNAL");
        assertTrue(kind.getEnumValues().contains("BUSINESS"), "Menu.kind should offer BUSINESS");
        assertTrue(menu.getFields().stream().anyMatch(f -> "parentMenuId".equals(f.getName())),
                "Menu should carry a soft parentMenuId pointer for hierarchy");

        assertTrue(propertyValue.getFields().stream().anyMatch(f -> "scopeType".equals(f.getName())),
                "PropertyValue should carry the cascade's scopeType");
        assertTrue(propertyValue.getFields().stream().anyMatch(f -> "scopeId".equals(f.getName())),
                "PropertyValue should carry the cascade's scopeId");
        assertTrue(propertyValue.getFields().stream().anyMatch(f -> "propKey".equals(f.getName())),
                "PropertyValue should be a generic key/value (propKey)");
        assertTrue(propertyValue.getFields().stream().anyMatch(f -> "propValue".equals(f.getName())),
                "PropertyValue should be a generic key/value (propValue)");
    }

    private static CompiledField field(CompiledConcept concept, String name) {
        return concept.getFields().stream()
                .filter(f -> name.equals(f.getName()))
                .findFirst()
                .orElseThrow();
    }

    private Path write(String relative, String content) throws Exception {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
