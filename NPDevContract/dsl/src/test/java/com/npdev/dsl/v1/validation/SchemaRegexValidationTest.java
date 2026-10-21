package com.npdev.dsl.v1.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * REG-274: a schema {@code regex} is enforced on write, so one that does not compile is refused at
 * model-validation time rather than turning every write of that field into a failure (or, worse,
 * silently matching nothing).
 */
class SchemaRegexValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static String model(String domainRegex, String nestedRegex) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "regex.validation", "version": "1.0",
              "domainTypes": [
                { "name": "Code", "baseType": "string", "validation": { "type": "string", "regex": "%s" } }
              ],
              "concepts": [
                { "name": "Thing", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "code", "type": "string", "domainType": "Code" },
                  { "name": "profile", "type": "object", "properties": {
                      "nick": { "type": "string", "regex": "%s" } } }
                ] }
              ]
            }
            """.formatted(domainRegex, nestedRegex);
    }

    @Test
    void anUncompilableRegexIsRefusedOnADomainTypeAndANestedProperty() throws Exception {
        List<String> errors = validate(model("(open", "*bad"));

        assertTrue(errors.stream().anyMatch(e -> e.contains("Domain type Code validation") && e.contains("not a valid regular expression")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Thing field profile.nick") && e.contains("not a valid regular expression")), errors.toString());
    }

    @Test
    void aCompilableRegexIsAccepted() throws Exception {
        List<String> errors = validate(model("^[A-Z0-9-]+$","^\\\\d+$"));

        assertTrue(errors.stream().noneMatch(e -> e.contains("regular expression")), errors.toString());
    }
}
