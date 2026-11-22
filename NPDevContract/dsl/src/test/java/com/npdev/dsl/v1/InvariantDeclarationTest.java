package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An invariant is a named rule, and a rule needs a kind: either an explicit {@code type} (such as unique) or an
 * {@code expr}, which implies the expression kind. A declaration with neither says nothing the platform could enforce,
 * so the model schema refuses it, naming both keywords, instead of an empty rule reaching the compiled model.
 */
class InvariantDeclarationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void anInvariantWithNeitherATypeNorAnExpressionIsRefusedNamingBoth() throws Exception {
        String json = """
            {
              "dslVersion": "3.0.0", "namespace": "demo.invariant", "version": "1.0",
              "concepts": [
                { "name": "Order",
                  "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ],
                  "invariants": [ { "name": "Empty" } ] }
              ]
            }
            """;

        IOException refused = assertThrows(IOException.class,
                () -> new JsonModelParser().parse(MAPPER.readTree(json)));

        // The message is localized, but the two keyword names are quoted in every language.
        assertTrue(refused.getMessage().contains("'type'") && refused.getMessage().contains("'expr'"), refused.getMessage());
    }

    @Test
    void anInvariantWithOnlyAnExpressionIsTheExpressionKind() throws Exception {
        String json = """
            {
              "dslVersion": "3.0.0", "namespace": "demo.invariant", "version": "1.0",
              "concepts": [
                { "name": "Order",
                  "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                              { "name": "total", "type": "integer" } ],
                  "invariants": [ { "name": "NonNegative", "expr": "total >= 0" } ] }
              ]
            }
            """;

        var ast = new JsonModelParser().parse(MAPPER.readTree(json));

        var invariant = ast.getConcepts().get(0).getInvariants().get(0);
        assertTrue("expression".equalsIgnoreCase(invariant.getType()), invariant.getType());
    }
}
