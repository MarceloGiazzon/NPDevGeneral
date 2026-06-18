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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the canonical built-in identity pack ({@code NPDevContract/packs/identity/pack.json})
 * parses, validates, and compiles, with its User/Role/UserRole bonds resolving. The test loads the
 * real artifact (not an inline copy) so the shipped pack is actually exercised.
 */
class IdentityPackResolutionTest {

    @TempDir
    Path temp;

    @Test
    void builtInIdentityPackCompilesWithResolvedBonds() throws Exception {
        Path realPack = Path.of("..", "packs", "identity", "pack.json").toAbsolutePath().normalize();
        assertTrue(Files.exists(realPack), "Built-in identity pack must exist at " + realPack);

        write("packs/identity/pack.json", Files.readString(realPack));
        Path model = write("model.json", """
                {
                  "namespace": "identity.pack.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/identity/pack.json", "as": "id" }
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
        assertTrue(errors.isEmpty(), "Expected identity pack model to validate, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledConcept user = compiled.findConcept("id::User").orElseThrow();
        CompiledConcept role = compiled.findConcept("id::Role").orElseThrow();
        CompiledConcept userRole = compiled.findConcept("id::UserRole").orElseThrow();

        assertTrue(user.getFields().stream().anyMatch(f -> "username".equals(f.getName()) && f.isUnique()),
                "User.username should be unique");
        assertTrue(role.getFields().stream().anyMatch(f -> "name".equals(f.getName()) && f.isUnique()),
                "Role.name should be unique");

        CompiledField userId = field(userRole, "userId");
        assertEquals("id::User", userId.getReferenceSemantics().getTarget());
        assertEquals("cascade", userId.getReferenceSemantics().getOnDelete());

        CompiledField roleId = field(userRole, "roleId");
        assertEquals("id::Role", roleId.getReferenceSemantics().getTarget());
        assertEquals("restrict", roleId.getReferenceSemantics().getOnDelete());
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
