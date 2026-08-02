package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** LNCH-13: compile-time validation of a concept's declarative access:{read,write} rules. */
class ConceptAccessValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static String modelWithAccess(String accessJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.access", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "ownerId", "type": "string" },
                  { "name": "siteId", "type": "string" },
                  { "name": "total", "type": "integer" } ],
                  "access": %s }
              ]
            }
            """.formatted(accessJson);
    }

    @Test
    void validAccessRuleWithUserPseudoVariablePasses() throws Exception {
        List<String> errors = validate(modelWithAccess(
                "{\"read\": \"ownerId == $user.id\", \"write\": \"ownerId == $user.id\"}"));
        assertTrue(errors.stream().noneMatch(e -> e.contains("access.")),
                "unexpected access rule error, got: " + errors);
    }

    @Test
    void compoundAccessRuleWithMultiplePseudoVariablesPasses() throws Exception {
        List<String> errors = validate(modelWithAccess(
                "{\"read\": \"ownerId == $user.id && siteId == $user.tenantId\"}"));
        assertTrue(errors.stream().noneMatch(e -> e.contains("access.")),
                "unexpected access rule error, got: " + errors);
    }

    @Test
    void unknownFieldInAccessRuleIsCaught() throws Exception {
        List<String> errors = validate(modelWithAccess("{\"read\": \"bogusField == $user.id\"}"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("access.read") && e.contains("references unknown field bogusField")),
                "expected an unknown-field error, got: " + errors);
    }

    @Test
    void nonBooleanAccessRuleIsRejected() throws Exception {
        List<String> errors = validate(modelWithAccess("{\"read\": \"total + 1\"}"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("access.read") && e.contains("must evaluate to a boolean")),
                "expected a boolean-shape error, got: " + errors);
    }

    @Test
    void syntaxErrorInAccessRuleIsCaught() throws Exception {
        List<String> errors = validate(modelWithAccess("{\"write\": \"ownerId ==\"}"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("access.write") && e.contains("syntax error")),
                "expected a syntax error, got: " + errors);
    }

    /**
     * RC-A4 hard rule (Move 14 Phase C item C1): {@code $prop.*} must never be usable inside
     * {@code access.read}/{@code access.write} -- a property (even a non-securityRelevant one, per
     * PropertyResolverController#authorizeWrite's own design, is mutable by a non-admin user for
     * their own scope) must never be able to flip an authorization decision, or the authoring
     * contract's rule A9 has a hole with a UI on it. "Verified RED on both keys" per the rule's own
     * instruction -- the two tests below are that proof, not just one shared assertion.
     */
    private static String modelWithAccessAndProperty(String accessJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.access", "version": "1.0",
              "propertyScopes": [ { "name": "tenant" } ],
              "properties": [
                { "name": "pageRows", "type": "int", "default": 25, "settableAt": ["tenant"] }
              ],
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "ownerId", "type": "string" },
                  { "name": "siteId", "type": "string" },
                  { "name": "total", "type": "integer" } ],
                  "access": %s }
              ]
            }
            """.formatted(accessJson);
    }

    @Test
    void propReferenceInAccessReadIsRefused() throws Exception {
        List<String> errors = validate(modelWithAccessAndProperty(
                "{\"read\": \"total > $prop.pageRows\"}"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("access.read") && e.contains("$prop.pageRows")
                        && e.contains("forbidden")),
                "expected $prop.* to be refused in access.read, got: " + errors);
    }

    @Test
    void propReferenceInAccessWriteIsRefused() throws Exception {
        List<String> errors = validate(modelWithAccessAndProperty(
                "{\"write\": \"total > $prop.pageRows\"}"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("access.write") && e.contains("$prop.pageRows")
                        && e.contains("forbidden")),
                "expected $prop.* to be refused in access.write, got: " + errors);
    }

    @Test
    void modelWithNoAccessBlockHasNoAccessErrors() throws Exception {
        List<String> errors = validate("""
            {
              "dslVersion": "1.0.0", "namespace": "wms.access", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """);
        assertTrue(errors.stream().noneMatch(e -> e.contains("access.")),
                "unexpected access rule error for a concept with no access block, got: " + errors);
    }
}
