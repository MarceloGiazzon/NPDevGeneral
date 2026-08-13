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
        // LNCH-4: PasswordResetToken.userId must bond to User the same way UserRole.userId does.
        CompiledConcept passwordResetToken = compiled.findConcept("id::PasswordResetToken").orElseThrow();

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

        assertTrue(passwordResetToken.getFields().stream().anyMatch(f -> "tokenHash".equals(f.getName()) && f.isUnique()),
                "PasswordResetToken.tokenHash should be unique");
        CompiledField tokenUserId = field(passwordResetToken, "userId");
        assertEquals("id::User", tokenUserId.getReferenceSemantics().getTarget());
        assertEquals("cascade", tokenUserId.getReferenceSemantics().getOnDelete());
    }

    /**
     * PK-2: the physical SQL identity of a pack-derived concept must depend on the pack's own
     * {@code pack} id + major version, never the importing app's chosen alias. Compiles the SAME
     * real identity pack twice -- once unaliased, once {@code as: "auth"} -- and asserts both
     * produce the byte-identical physical table name. Before this card, the two would have compiled
     * to {@code identity_users} and {@code auth_users} respectively: one pack, two apps,
     * incompatible physical schemas.
     */
    @Test
    void identityPackPhysicalTableNameIsIndependentOfImportAlias() throws Exception {
        Path realPack = Path.of("..", "packs", "identity", "pack.json").toAbsolutePath().normalize();
        String packJson = Files.readString(realPack);

        write("unaliased/packs/identity/pack.json", packJson);
        Path unaliasedModel = write("unaliased/model.json", """
                {
                  "namespace": "identity.pack.unaliased",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/identity/pack.json" }
                  ]
                }
                """);

        write("aliased/packs/identity/pack.json", packJson);
        Path aliasedModel = write("aliased/model.json", """
                {
                  "namespace": "identity.pack.aliased",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/identity/pack.json", "as": "auth" }
                  ]
                }
                """);

        CompiledModel unaliasedCompiled = compile(unaliasedModel);
        CompiledModel aliasedCompiled = compile(aliasedModel);

        CompiledConcept unaliasedUser = unaliasedCompiled.findConcept("identity::User").orElseThrow();
        CompiledConcept aliasedUser = aliasedCompiled.findConcept("auth::User").orElseThrow();

        assertEquals("identity_v1_users", unaliasedUser.getTableName());
        assertEquals("identity_v1_users", aliasedUser.getTableName(),
                "the same pack imported under a different alias must produce the identical physical table name");
    }

    private static CompiledModel compile(Path model) throws Exception {
        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(model);
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected model to validate, got: " + errors);
        return new ModelCompiler().compile(ast);
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
