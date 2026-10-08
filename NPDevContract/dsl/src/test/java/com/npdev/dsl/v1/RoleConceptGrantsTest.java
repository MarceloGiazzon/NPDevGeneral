package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiled.CompiledRole;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Roles and users: {@code roles[].concepts} (concept name -> read/create/update/delete) survives
 * parse -> validate -> compile -> canonical JSON round trip, a role may carry concepts without any
 * platform grants, and bad entries are compile errors naming the role.
 */
class RoleConceptGrantsTest {

    private static final String CONCEPTS = """
              "concepts": [
                { "name": "Mosaic", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] },
                { "name": "Cap", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
              ],
            """;

    private static ModelAst parse(Path dir, String roles) throws Exception {
        Path modelPath = dir.resolve("model.json");
        Files.writeString(modelPath, """
                {
                  "namespace": "roles.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                %s
                  "roles": %s
                }
                """.formatted(CONCEPTS, roles));
        return new JsonModelParser().parse(modelPath);
    }

    @Test
    void conceptGrantsSurviveTheWholePipeline(@TempDir Path dir) throws Exception {
        ModelAst ast = parse(dir, """
                [
                  { "name": "Member", "grants": ["EXECUTE_FLOW"], "concepts": { "Mosaic": ["read", "create", "update"] } },
                  { "name": "Visitor", "concepts": { "Mosaic": ["read"] } }
                ]
                """);

        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "expected no errors, got: " + validation.getErrors());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledModel reread = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        Map<String, CompiledRole> byName = new java.util.HashMap<>();
        reread.getRoles().forEach(role -> byName.put(role.name(), role));

        assertEquals(Map.of("Mosaic", List.of("read", "create", "update")), byName.get("Member").concepts());
        assertEquals(List.of("EXECUTE_FLOW"), byName.get("Member").grants());
        assertEquals(Map.of("Mosaic", List.of("read")), byName.get("Visitor").concepts());
        assertTrue(byName.get("Visitor").grants().isEmpty(), "a concepts-only role needs no platform grants");
    }

    @Test
    void anUnknownConceptOrAnEmptyRoleIsACompileError(@TempDir Path dir) throws Exception {
        ModelAst ast = parse(dir, """
                [
                  { "name": "Member", "concepts": { "Ghost": ["read"] } },
                  { "name": "Empty" }
                ]
                """);

        String all = String.join("\n", new SemanticValidator().validateWithWarnings(ast).getErrors());
        assertTrue(all.contains("Role Member: concepts names unknown concept Ghost"), all);
        assertTrue(all.contains("Role Empty: grants must not be empty"), all);
    }

    @Test
    void badOperationListsAreRejectedByTheSchema(@TempDir Path dir) {
        Exception rejected = assertThrows(Exception.class, () -> parse(dir, """
                [ { "name": "Member", "concepts": { "Cap": ["read", "read", "erase"], "Mosaic": [] } } ]
                """));
        String message = rejected.getMessage();
        assertTrue(message.contains("$.roles[0].concepts.Cap[2]"), message);
        assertTrue(message.contains("$.roles[0].concepts.Mosaic"), message);
    }
}
