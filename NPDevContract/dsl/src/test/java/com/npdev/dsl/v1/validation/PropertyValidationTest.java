package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 12 P4 (item 12 / RC-A1, {@code MOVE11_RUNTIME_CONFIGURATION_PLAN} Part A.1): the three
 * compile-time refusals A1's own DoD names, plus the positive control -- a well-formed declaration
 * validates clean. Goes through the real {@link JsonModelParser} + {@link SemanticValidator} front
 * door, per the REG-89 lesson (kernel-only/AST-only tests cannot see a validator gap).
 */
class PropertyValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String propertyScopesJson, String propertiesJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.props", "version": "1.0",
              "concepts": [
                { "name": "Widget", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "propertyScopes": [ %s ],
              "properties": [ %s ]
            }
            """.formatted(propertyScopesJson, propertiesJson);
    }

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static final String TENANT_SCOPE = "{ \"name\": \"tenant\" }";
    private static final String USER_SCOPE = "{ \"name\": \"user\", \"from\": \"$user.id\" }";
    private static final String ESTABELECIMENTO_SCOPE =
            "{ \"name\": \"estabelecimento\", \"from\": \"$user.estabelecimentoId\" }";

    @Test
    void aWellFormedDeclarationValidatesClean() throws Exception {
        List<String> errors = validate(modelJson(
                USER_SCOPE + "," + TENANT_SCOPE,
                "{ \"name\": \"pageRows\", \"type\": \"int\", \"default\": 25, \"settableAt\": [\"tenant\", \"user\"] }"
        ));
        assertTrue(errors.stream().noneMatch(e -> e.contains("propertyScopes") || e.contains("properties")),
                "unexpected: " + errors);
    }

    @Test
    void settableAtNamingAnUndeclaredScopeFailsWithASuggestedFix() throws Exception {
        List<String> errors = validate(modelJson(
                TENANT_SCOPE,
                "{ \"name\": \"pageRows\", \"type\": \"int\", \"default\": 25, \"settableAt\": [\"tenant\", \"estabelecimento\"] }"
        ));
        assertTrue(errors.stream().anyMatch(e ->
                        e.contains("settableAt names undeclared scope 'estabelecimento'") && e.contains("suggestedFix")),
                "a settableAt naming an undeclared scope must be refused with a suggestedFix: " + errors);
    }

    @Test
    void aDefaultNotMatchingTheDeclaredTypeFailsCompilation() throws Exception {
        List<String> errors = validate(modelJson(
                TENANT_SCOPE,
                "{ \"name\": \"requireConferenciaDupla\", \"type\": \"boolean\", \"default\": \"not-a-boolean\", "
                        + "\"settableAt\": [\"tenant\"] }"
        ));
        assertTrue(errors.stream().anyMatch(e ->
                        e.contains("requireConferenciaDupla") && e.contains("does not match declared type 'boolean'")),
                "a default whose value does not match the declared type must be refused: " + errors);
    }

    @Test
    void aFromExpressionOutsideTheContextGrammarFailsCompilation() throws Exception {
        List<String> errors = validate(modelJson(
                "{ \"name\": \"estabelecimento\", \"from\": \"$db.lookup(estabelecimentoId)\" }",
                "{ \"name\": \"pageRows\", \"type\": \"int\", \"default\": 25, \"settableAt\": [\"estabelecimento\"] }"
        ));
        assertTrue(errors.stream().anyMatch(e ->
                        e.contains("is not a form ExecutionContext can supply")),
                "a from expression outside the $ctx.tenantId/$user.id/$user.<tagName> grammar (e.g. a "
                        + "per-read database lookup) must be refused at compile time, not attempted at "
                        + "runtime: " + errors);
    }

    @Test
    void anUnknownTypeFailsCompilation() {
        // "type" is itself a closed JSON-schema enum (string/int/boolean/enum/date), so an
        // out-of-set value is refused even earlier than SemanticValidator -- at schema validation,
        // inside JsonModelParser.parse() itself. Still a compile-time refusal, just one layer up.
        Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> validate(modelJson(
                TENANT_SCOPE,
                "{ \"name\": \"pageRows\", \"type\": \"object\", \"default\": {}, \"settableAt\": [\"tenant\"] }"
        )));
        assertTrue(thrown.getMessage() != null && thrown.getMessage().toLowerCase().contains("type"),
                "a type outside the closed set (string/int/boolean/enum/date) must be refused at compile "
                        + "time: " + thrown);
    }

    @Test
    void implicitRootScopeDeclaredBeforeAMoreSpecificOneFailsCompilation() throws Exception {
        // REG-116: the implicit root scope (no "from") is the least specific by definition and must
        // be declared LAST -- propertyScopes' order IS resolution order, and nothing else signals
        // which entry is "more specific" than another, so this compiles clean and silently inverts
        // precedence at runtime if not caught here. Found live in dsl-conformance-max's own
        // pre-existing declaration (tenant before user) once Move 14's PropertyResolver (RC-A3)
        // finally exercised propertyScopes' order for the first time.
        List<String> errors = validate(modelJson(
                TENANT_SCOPE + "," + USER_SCOPE,
                "{ \"name\": \"pageRows\", \"type\": \"int\", \"default\": 25, \"settableAt\": [\"tenant\", \"user\"] }"
        ));
        assertTrue(errors.stream().anyMatch(e ->
                        e.contains("must be declared LAST in propertyScopes") && e.contains("position 1 of 2")),
                "a root scope declared before a more specific one must be refused: " + errors);
    }

    @Test
    void implicitRootScopeDeclaredLastValidatesClean() throws Exception {
        List<String> errors = validate(modelJson(
                USER_SCOPE + "," + TENANT_SCOPE,
                "{ \"name\": \"pageRows\", \"type\": \"int\", \"default\": 25, \"settableAt\": [\"tenant\", \"user\"] }"
        ));
        assertTrue(errors.stream().noneMatch(e -> e.contains("propertyScopes")), "unexpected: " + errors);
    }

    @Test
    void threeScopesResolveInDeclaredOrderMostSpecificFirst() throws Exception {
        List<String> errors = validate(modelJson(
                USER_SCOPE + "," + ESTABELECIMENTO_SCOPE + "," + TENANT_SCOPE,
                "{ \"name\": \"pageRows\", \"type\": \"int\", \"default\": 25, "
                        + "\"settableAt\": [\"tenant\", \"estabelecimento\", \"user\"] }"
        ));
        assertTrue(errors.stream().noneMatch(e -> e.contains("propertyScopes") || e.contains("properties")),
                "unexpected: " + errors);
    }
}
