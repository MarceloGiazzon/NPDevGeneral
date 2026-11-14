package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FORM P0 (REG-339): the {@code system} concept flag parses, defaults to false, and validates. */
class SystemConceptTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelAst parse(String conceptsJson) throws Exception {
        String json = """
            { "dslVersion": "1.0.0", "namespace": "system.concept", "version": "1.0",
              "concepts": [ %s ] }
            """.formatted(conceptsJson);
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    @Test
    void systemFlagParsesAndDefaultsToFalse() throws Exception {
        ModelAst ast = parse("""
            { "name": "Vault", "system": true, "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
            { "name": "Note", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
            """);
        ConceptAst vault = ast.getConcepts().stream().filter(c -> c.getName().equals("Vault")).findFirst().orElseThrow();
        ConceptAst note = ast.getConcepts().stream().filter(c -> c.getName().equals("Note")).findFirst().orElseThrow();
        assertTrue(vault.isSystem());
        assertFalse(note.isSystem());
    }

    @Test
    void anAppConceptWithAPasswordHashIsAllowedWhenTheIdentityPackIsNotComposed() throws Exception {
        // the rule only fires once the identity pack owns credentials; a plain model is unaffected
        ModelAst ast = parse("""
            { "name": "Account", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "passwordHash", "type": "string", "required": false } ] }
            """);
        List<String> errors = new SemanticValidator().validate(ast);
        assertEquals(List.of(), errors.stream().filter(e -> e.contains("password hash")).toList());
    }
}
