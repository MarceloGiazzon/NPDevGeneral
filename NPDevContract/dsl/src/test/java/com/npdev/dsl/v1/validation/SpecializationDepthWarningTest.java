package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-3 (owner 2026-11-21): specialization ("is a new kind of thing", one parent) is limited to two levels; anything
 * deeper should be a facet. The limit is a WARNING, never an error, so a deeper chain still validates.
 */
class SpecializationDepthWarningTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String concept(String name, String parent) {
        // A specialization is add-only: it inherits `id` and must not redeclare it.
        return parent == null
                ? "{ \"name\": \"" + name + "\", \"fields\": [ { \"name\": \"id\", \"type\": \"uuid\", \"id\": true, \"required\": true } ] }"
                : "{ \"name\": \"" + name + "\", \"specializes\": \"" + parent + "\", \"fields\": [ { \"name\": \"f" + name
                + "\", \"type\": \"string\" } ] }";
    }

    private static ValidationResult validate(int links) throws Exception {
        String[] names = {"A", "B", "C", "D", "E"};
        StringBuilder concepts = new StringBuilder(concept(names[0], null));
        for (int i = 1; i <= links; i++) {
            concepts.append(",").append(concept(names[i], names[i - 1]));
        }
        String json = """
            { "dslVersion": "3.0.0", "namespace": "d3.depth", "version": "1.0", "concepts": [ %s ] }
            """.formatted(concepts);
        return new SemanticValidator().validateWithWarnings(new JsonModelParser().parse(MAPPER.readTree(json)));
    }

    @Test
    void twoLinksDoNotWarn() throws Exception {
        List<String> warnings = validate(2).getWarnings();
        assertFalse(warnings.stream().anyMatch(w -> w.contains("levels deep")), "got: " + warnings);
    }

    @Test
    void threeLinksWarnNamingTheChainAndTheFacetAlternativeButStillValidate() throws Exception {
        ValidationResult result = validate(3);
        assertTrue(result.getErrors().isEmpty(), "a warning never refuses: " + result.getErrors());
        List<String> warnings = result.getWarnings();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Concept D specializes 3 levels deep")
                && w.contains("D -> C -> B -> A") && w.contains("facets") && w.contains("augments")), "got: " + warnings);
    }

    @Test
    void fourLinksWarnOnEachConceptPastTheLimit() throws Exception {
        List<String> warnings = validate(4).getWarnings();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Concept D specializes 3 levels deep")), "got: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Concept E specializes 4 levels deep")), "got: " + warnings);
    }
}
