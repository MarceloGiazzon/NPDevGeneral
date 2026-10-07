package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiled.CompiledPublicRead;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P6 (G4): {@code access.public} -- parse, compile-time checks, canonical JSON round trip. */
class PublicReadValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static List<String> validate(String json) throws Exception {
        return new SemanticValidator().validate(parse(json));
    }

    private static String model(String mosaicPublic, String cellPublic) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "demo.publicread", "version": "1.0",
              "concepts": [
                { "name": "Mosaic", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "title", "type": "string" },
                  { "name": "ownerEmail", "type": "string", "access": { "read": "$user.roles.contains('Admin')" } },
                  { "name": "status", "type": "string" } ],
                  "access": { "read": "status != 'DRAFT'", "public": %s } },
                { "name": "MosaicCell", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "mosaicId", "type": "reference", "reference": { "target": "Mosaic" } },
                  { "name": "row", "type": "integer" } ]%s }
              ],
              "aggregates": [
                { "name": "MosaicAggregate", "root": "Mosaic",
                  "collections": [ { "name": "cells", "concept": "MosaicCell", "childField": "mosaicId" } ] }
              ]
            }
            """.formatted(mosaicPublic, cellPublic == null ? "" : ", \"access\": { \"public\": " + cellPublic + " }");
    }

    private static List<String> publicErrors(List<String> errors) {
        return errors.stream().filter(e -> e.contains("access.public")).toList();
    }

    @Test
    void validPublicGrantPassesAndRoundTripsThroughCanonicalJson() throws Exception {
        String json = model("{ \"where\": \"status == 'PUBLISHED'\", \"fields\": [\"title\", \"status\"] }",
                "{ \"fields\": [\"row\"], \"scope\": \"aggregate\" }");
        assertEquals(List.of(), publicErrors(validate(json)));

        CompiledModel compiled = new ModelCompiler().compile(parse(json));
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        CompiledPublicRead mosaic = restored.findConcept("Mosaic").map(CompiledConcept::getAccess).orElseThrow().getPublicRead();
        assertEquals("status == 'PUBLISHED'", mosaic.where());
        assertEquals(List.of("title", "status"), mosaic.fields());
        assertTrue(mosaic.directlyReadable());
        CompiledPublicRead cell = restored.findConcept("MosaicCell").map(CompiledConcept::getAccess).orElseThrow().getPublicRead();
        assertNull(cell.where());
        assertFalse(cell.directlyReadable(), "scope aggregate must not get its own public routes");
        assertNull(restored.findConcept("MosaicCell").get().getAccess().getRead());
    }

    @Test
    void conceptWithoutPublicGrantKeepsNoPublicKeyInCanonicalJson() throws Exception {
        String json = model("null", null).replace(", \"public\": null", "");
        CompiledModel compiled = new ModelCompiler().compile(parse(json));
        assertFalse(CompiledModelCanonicalJson.toJson(compiled).contains("\"public\""));
    }

    @Test
    void unknownFieldAndFieldWithOwnReadRuleAreRefused() throws Exception {
        List<String> errors = publicErrors(validate(model("{ \"fields\": [\"title\", \"nope\", \"ownerEmail\"] }", null)));
        assertTrue(errors.stream().anyMatch(e -> e.contains("unknown field 'nope'")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("'ownerEmail' declares its own access.read")), errors.toString());
    }

    @Test
    void whereReferencingTheCallerOrOutsideTheGrammarIsRefused() throws Exception {
        assertTrue(publicErrors(validate(model("{ \"where\": \"title == $user.id\", \"fields\": [\"title\"] }", null)))
                .stream().anyMatch(e -> e.contains("must not reference $user")));
        assertTrue(publicErrors(validate(model("{ \"where\": \"missing == 'x'\", \"fields\": [\"title\"] }", null)))
                .stream().anyMatch(e -> e.contains("unknown field 'missing'")));
        assertTrue(publicErrors(validate(model("{ \"where\": \"(title == 'x'\", \"fields\": [\"title\"] }", null)))
                .stream().anyMatch(e -> e.contains("outside the queries[].where grammar")));
    }

    @Test
    void aggregateScopeOnAConceptThatIsNoAggregateChildIsRefused() throws Exception {
        List<String> errors = publicErrors(validate(model("{ \"fields\": [\"title\"], \"scope\": \"aggregate\" }", null)));
        assertTrue(errors.stream().anyMatch(e -> e.contains("not a child collection of any aggregate")), errors.toString());
    }
}
