package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an author writes to shape a form and to rule an array must reach the compiled model unchanged: the width and
 * summary-card hint of a field, the boxes a reference picker searches, the way a concept is shown by default, an array
 * whose elements must all differ, and an array of objects identified by one property with duplicates denied. Each is
 * a keyword nothing else in the unit tests names, and each is silently lost if the parser or compiler forgets it.
 */
class FieldHintsAndArrayRulesSurviveCompileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "3.0.0", "namespace": "demo.hints", "version": "1.0",
          "domainTypes": [ { "name": "Sku", "baseType": "string", "format": "sku" } ],
          "concepts": [
            { "name": "Owner",
              "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                          { "name": "name", "type": "string", "required": true } ] },
            { "name": "Pet",
              "ui": { "label": "Pet", "displayMode": "standard" },
              "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "name", "type": "string", "required": true,
                  "ui": { "label": "Name", "width": "12rem", "summaryCard": true } },
                { "name": "owner", "type": "reference", "required": false,
                  "reference": { "target": "Owner", "displayField": "name" },
                  "ui": { "label": "Owner", "searchFields": [ "name" ] } },
                { "name": "nicknames", "type": "array", "required": false, "uniqueItems": true,
                  "items": { "type": "enum", "enumValues": [ "rex", "fido", "luna" ] } },
                { "name": "vaccines", "type": "array", "required": false,
                  "itemIdentityField": "code", "duplicationPolicy": "deny",
                  "items": { "type": "object", "required": [ "code" ],
                             "properties": { "code": { "type": "string" }, "given": { "type": "date" } } } }
              ] }
          ]
        }
        """;

    private static CompiledModel compile() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL));
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), errors.toString());
        return new ModelCompiler().compile(ast);
    }

    private static CompiledField field(CompiledModel model, String concept, String name) {
        CompiledConcept compiled = model.findConcept(concept).orElseThrow();
        return compiled.getFields().stream().filter(f -> f.getName().equals(name)).findFirst().orElseThrow();
    }

    /** Every value the canonical JSON holds under {@code key}, wherever it sits in the tree. */
    private static List<JsonNode> valuesOf(JsonNode node, String key) {
        List<JsonNode> found = new ArrayList<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getKey().equals(key)) {
                    found.add(entry.getValue());
                }
                found.addAll(valuesOf(entry.getValue(), key));
            });
        } else if (node.isArray()) {
            node.forEach(child -> found.addAll(valuesOf(child, key)));
        }
        return found;
    }

    @Test
    void aFieldsWidthAndSummaryCardHintsReachTheCompiledField() throws Exception {
        CompiledField name = field(compile(), "Pet", "name");

        assertEquals("12rem", name.getUi().getWidth());
        assertEquals(Boolean.TRUE, name.getUi().getSummaryCard());
    }

    @Test
    void aReferencePickerKeepsTheBoxesItSearches() throws Exception {
        CompiledField owner = field(compile(), "Pet", "owner");

        assertEquals(List.of("name"), owner.getUi().getSearchFields());
    }

    @Test
    void theWayAConceptIsShownByDefaultSurvivesInTheCanonicalJson() throws Exception {
        JsonNode canonical = MAPPER.readTree(CompiledModelCanonicalJson.toJson(compile()));

        assertTrue(valuesOf(canonical, "displayMode").stream().anyMatch(v -> "standard".equals(v.asText())),
                "displayMode standard must reach the canonical JSON");
    }

    @Test
    void anArrayWithUniqueItemsAndAnIdentifiedObjectArrayKeepTheirRules() throws Exception {
        JsonNode canonical = MAPPER.readTree(CompiledModelCanonicalJson.toJson(compile()));

        assertTrue(valuesOf(canonical, "uniqueItems").stream().anyMatch(JsonNode::asBoolean), "uniqueItems true");
        assertTrue(valuesOf(canonical, "itemIdentityField").stream().anyMatch(v -> "code".equals(v.asText())),
                "itemIdentityField code");
        assertTrue(valuesOf(canonical, "duplicationPolicy").stream().anyMatch(v -> "deny".equals(v.asText())),
                "duplicationPolicy deny");
    }

    @Test
    void aDomainTypeKeepsTheStoredTypeItsFieldsGet() throws Exception {
        CompiledModel model = compile();

        assertEquals("string", model.getDomainTypes().stream()
                .filter(type -> type.getName().equals("Sku")).findFirst().orElseThrow().getBaseType());
    }
}
