package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-16 (parent-scope half): {@code parentField('<referenceField>', '<targetField>')} is valid only in a
 * concept's {@code access.write}, with a reference field of that concept and a field of the concept it
 * points at. The runtime denies a write whose parent cannot be found, so every mistake caught here is a
 * lock-out that would otherwise only show up as a 403 in production.
 */
class ParentFieldValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String visitAccessJson, String visitFieldAccessJson) throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "trial.parent", "version": "1.0",
              "concepts": [
                { "name": "Participant", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "coordinator", "type": "string" } ] },
                { "name": "Visit", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "participantId", "type": "reference", "reference": { "target": "Participant" } },
                  { "name": "coordinator", "type": "string" },
                  { "name": "note", "type": "string" %s } ],
                  "access": %s }
              ]
            }
            """.formatted(visitFieldAccessJson, visitAccessJson);
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static List<String> validateWrite(String writeRule) throws Exception {
        return validate("{\"write\": \"" + writeRule.replace("\"", "\\\"") + "\"}", "");
    }

    private static boolean mentions(List<String> errors, String... fragments) {
        return errors.stream().anyMatch(e -> {
            for (String fragment : fragments) {
                if (!e.contains(fragment)) {
                    return false;
                }
            }
            return true;
        });
    }

    @Test
    void parentFieldInAccessWriteWithAReferenceAndATargetFieldPasses() throws Exception {
        List<String> errors = validateWrite("parentField('participantId', 'coordinator') == $user.id");

        assertTrue(errors.stream().noneMatch(e -> e.contains("access.") || e.contains("parentField")),
                "a well-formed parentField must validate clean, got: " + errors);
    }

    @Test
    void parentFieldMayBeCombinedWithTheRowsOwnFields() throws Exception {
        List<String> errors = validateWrite(
                "coordinator == $user.id || parentField('participantId', 'coordinator') == $user.id");

        assertTrue(errors.stream().noneMatch(e -> e.contains("access.") || e.contains("parentField")), errors.toString());
    }

    @Test
    void anUnknownReferenceFieldIsRefused() throws Exception {
        List<String> errors = validateWrite("parentField('participant', 'coordinator') == $user.id");

        assertTrue(mentions(errors, "access.write", "parentField", "unknown field 'participant'"), errors.toString());
    }

    @Test
    void aFieldThatIsNotAReferenceIsRefused() throws Exception {
        List<String> errors = validateWrite("parentField('note', 'coordinator') == $user.id");

        assertTrue(mentions(errors, "access.write", "'note' is not a reference field"), errors.toString());
    }

    @Test
    void aTargetFieldTheParentDoesNotHaveIsRefused() throws Exception {
        List<String> errors = validateWrite("parentField('participantId', 'owner') == $user.id");

        assertTrue(mentions(errors, "access.write", "Participant has no field 'owner'"), errors.toString());
    }

    @Test
    void malformedArgumentsAreRefused() throws Exception {
        for (String rule : new String[] {
                "parentField('participantId') == $user.id",
                "parentField(participantId, coordinator) == $user.id",
                "parentField('participantId', 'coordinator', 'x') == $user.id"}) {
            List<String> errors = validateWrite(rule);

            assertTrue(mentions(errors, "access.write", "takes exactly two quoted field names"), rule + " -> " + errors);
        }
    }

    @Test
    void parentFieldInAccessReadIsRefused() throws Exception {
        List<String> errors = validate("{\"read\": \"parentField('participantId', 'coordinator') == $user.id\"}", "");

        assertTrue(mentions(errors, "access.read", "only supported in access.write"), errors.toString());
    }

    @Test
    void parentFieldInAFieldLevelRuleIsRefused() throws Exception {
        List<String> errors = validate("{\"write\": \"coordinator == $user.id\"}",
                ", \"access\": {\"write\": \"parentField('participantId', 'coordinator') == $user.id\"}");

        assertTrue(mentions(errors, "field 'note'", "only supported in a concept's access.write"), errors.toString());
    }
}
