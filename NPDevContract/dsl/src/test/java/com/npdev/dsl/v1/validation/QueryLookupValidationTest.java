package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Friction #5 (Pigmentampas "beers to drink"): compile-time validation of {@code query.lookups[]}
 * and {@code query.computed[]} -- the per-key aggregate over another concept and the arithmetic
 * column that together express "cells needed minus caps owned".
 */
class QueryLookupValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String queryJson) throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "mosaic.lookup", "version": "1.0",
              "concepts": [
                { "name": "Cell", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "mosaic", "type": "string" },
                  { "name": "capId", "type": "string" } ] },
                { "name": "Owned", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "ownerUsername", "type": "string" },
                  { "name": "capId", "type": "string" },
                  { "name": "quantity", "type": "integer" },
                  { "name": "note", "type": "string" } ] }
              ],
              "queries": [ %s ]
            }
            """.formatted(queryJson);
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    @Test
    void neededMinusOwnedValidates() throws Exception {
        List<String> errors = validate("""
            { "name": "ToDrink", "concept": "Cell",
              "groupBy": [ "mosaic", "capId" ],
              "aggregates": [ { "name": "needed", "fn": "count" } ],
              "lookups": [ { "name": "owned", "concept": "Owned", "fn": "sum", "field": "quantity",
                             "on": { "capId": "capId" }, "where": "ownerUsername == $user.id" } ],
              "computed": [ { "name": "toDrink", "expression": "needed - owned" } ],
              "having": "toDrink > 0", "orderBy": [ "toDrink desc" ] }
            """);
        assertTrue(errors.stream().noneMatch(e -> e.contains("ToDrink")),
                "unexpected error on ToDrink (incl. orderBy/having naming a computed column), got: " + errors);
    }

    @Test
    void lookupOnAColumnThatIsNotAGroupByFieldIsRefused() throws Exception {
        List<String> errors = validate("""
            { "name": "ToDrink", "concept": "Cell",
              "groupBy": [ "mosaic" ],
              "aggregates": [ { "name": "needed", "fn": "count" } ],
              "lookups": [ { "name": "owned", "concept": "Owned", "fn": "sum", "field": "quantity",
                             "on": { "capId": "capId" } } ] }
            """);
        assertTrue(errors.stream().anyMatch(e -> e.contains("lookup owned") && e.contains("not an unbucketed groupBy field")),
                "expected an on-key error, got: " + errors);
    }

    @Test
    void lookupFieldErrorsAreNamed() throws Exception {
        List<String> errors = validate("""
            { "name": "ToDrink", "concept": "Cell",
              "groupBy": [ "capId" ],
              "aggregates": [ { "name": "needed", "fn": "count" } ],
              "lookups": [
                { "name": "a", "concept": "Owned", "fn": "sum", "field": "note", "on": { "capId": "capId" } },
                { "name": "b", "concept": "Owned", "fn": "sum", "on": { "bogus": "capId" } },
                { "name": "c", "concept": "Nope", "fn": "count", "on": { "capId": "capId" } } ] }
            """);
        assertTrue(errors.stream().anyMatch(e -> e.contains("lookup a") && e.contains("requires a numeric field")), "" + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("lookup b") && e.contains("requires a field")), "" + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("lookup b") && e.contains("bogus")), "" + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("lookup c") && e.contains("concept not found")), "" + errors);
    }

    @Test
    void computedMayOnlyNameEarlierColumns() throws Exception {
        List<String> errors = validate("""
            { "name": "ToDrink", "concept": "Cell",
              "groupBy": [ "capId" ],
              "aggregates": [ { "name": "needed", "fn": "count" } ],
              "computed": [ { "name": "x", "expression": "needed - owned" },
                            { "name": "needed", "expression": "1" },
                            { "name": "y", "expression": "needed -" } ] }
            """);
        assertTrue(errors.stream().anyMatch(e -> e.contains("computed x") && e.contains("names owned")), "" + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("computed needed") && e.contains("already used")), "" + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("computed y") && e.contains("cannot be parsed")), "" + errors);
    }

    @Test
    void lookupsOnAPlainQueryAreRefused() throws Exception {
        List<String> errors = validate("""
            { "name": "Plain", "concept": "Cell",
              "computed": [ { "name": "x", "expression": "1" } ] }
            """);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Plain") && e.contains("need an aggregate query")),
                "expected a plain-query refusal, got: " + errors);
    }
}
