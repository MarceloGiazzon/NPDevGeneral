package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F6-4: the workbench page edits three nesting levels (section, band, sub-band). An aggregate-bound
 * AutoPanel over a deeper tree still validates and saves, but warns that the deeper levels have no grid.
 */
class AggregateDepthWarningTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String concept(String name, String parentField) {
        return """
            { "name": "%s", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true }%s ] }""".formatted(name,
                parentField.isEmpty() ? "" : ", { \"name\": \"" + parentField + "\", \"type\": \"uuid\" }");
    }

    private static List<String> warningsFor(int levels) throws Exception {
        String[] names = {"Root", "L1", "L2", "L3", "L4"};
        StringBuilder concepts = new StringBuilder(concept("Root", ""));
        for (int i = 1; i <= levels; i++) {
            concepts.append(",").append(concept(names[i], "parentId"));
        }
        String nested = "";
        for (int i = levels; i >= 1; i--) {
            nested = """
                { "name": "c%d", "concept": "%s", "childField": "parentId", "ownership": "owned"%s }""".formatted(i, names[i],
                    nested.isEmpty() ? "" : ", \"collections\": [" + nested + "]");
        }
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "f6.depthwarn", "version": "1.0",
              "concepts": [ %s ],
              "aggregates": [ { "name": "Root", "root": "Root", "collections": [ %s ] } ],
              "autoPanels": [ { "aggregate": "Root" } ]
            }
            """.formatted(concepts, nested);
        return new SemanticValidator().validateWithWarnings(new JsonModelParser().parse(MAPPER.readTree(json))).getWarnings();
    }

    @Test
    void threeLevelsDoNotWarn() throws Exception {
        assertFalse(warningsFor(3).stream().anyMatch(w -> w.contains("levels deep")), "three levels are fully editable");
    }

    @Test
    void fourLevelsWarnThatTheDeepestHasNoGrid() throws Exception {
        List<String> warnings = warningsFor(4);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("nests collections 4 levels deep")
                && w.contains("three levels") && w.contains("no grid")), "got: " + warnings);
    }
}
