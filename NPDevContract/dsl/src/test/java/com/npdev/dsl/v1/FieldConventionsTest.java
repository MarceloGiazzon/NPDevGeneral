package com.npdev.dsl.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A concept field with no {@code type} is typed by the first {@code fieldConventions[]} rule matching its name. */
class FieldConventionsTest {

    @TempDir
    Path temp;

    private static final String ID = "{ \"name\": \"id\", \"type\": \"uuid\", \"id\": true, \"required\": true }";

    private String model(String rules, String fields) {
        return """
                {
                  "namespace": "demo", "dslVersion": "3.0.0", "version": "1.0",
                  %s
                  "concepts": [ { "name": "Customer", "fields": [ %s, %s ] } ]
                }
                """.formatted(rules.isEmpty() ? "" : "\"fieldConventions\": [" + rules + "],", ID, fields);
    }

    private Path write(String relative, String content) throws IOException {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private JsonNode field(JsonNode root, String name) {
        for (JsonNode f : root.get("concepts").get(0).get("fields")) {
            if (f.get("name").asText().equals(name)) {
                return f;
            }
        }
        throw new AssertionError("no field " + name);
    }

    private JsonNode resolve(String json) throws IOException {
        return new ModelSourceResolver().resolve(write("model.json", json)).resolvedRoot();
    }

    @Test
    void builtInRulesTypeNameAndDatesAndTheArrayDisappears() throws Exception {
        JsonNode root = resolve(model("", """
                { "name": "name" }, { "name": "clientName" }, { "name": "shippingAddressName" },
                { "name": "birthDt" }, { "name": "createdDtt" }
                """));
        assertEquals("string", field(root, "name").get("type").asText());
        assertEquals(50, field(root, "name").get("maxLength").asInt());
        assertEquals("string", field(root, "clientName").get("type").asText());
        assertEquals(50, field(root, "shippingAddressName").get("maxLength").asInt());
        assertEquals("date", field(root, "birthDt").get("type").asText());
        assertEquals("datetime", field(root, "createdDtt").get("type").asText());
        assertFalse(root.has("fieldConventions"));
    }

    @Test
    void everyConventionTypedFieldLeavesAnInfoNoteAndNothingElseDoes() throws Exception {
        var source = new ModelSourceResolver().resolve(write("model.json", model("""
                { "match": "*Amt", "defaults": { "type": "decimal", "precision": 18, "scale": 2 } }
                """, """
                { "name": "name" }, { "name": "birthDt" }, { "name": "totalAmt" }, { "name": "code", "type": "string" }
                """)));
        var notes = source.diagnostics();
        assertEquals(3, notes.size(), notes.toString());
        assertTrue(notes.stream().allMatch(n -> n.getSeverity() == com.npdev.dsl.v1.validation.ValidationSeverity.INFO));
        var birth = notes.stream().filter(n -> "birthDt".equals(n.getField())).findFirst().orElseThrow();
        assertEquals("FIELD_CONVENTION_APPLIED", birth.getCode());
        assertEquals("Customer", birth.getConcept());
        assertTrue(birth.getMessage().contains("built-in convention '*Dt'"), birth.getMessage());
        assertTrue(birth.getMessage().contains("type date"), birth.getMessage());
        var total = notes.stream().filter(n -> "totalAmt".equals(n.getField())).findFirst().orElseThrow();
        assertTrue(total.getMessage().contains("the model's convention '*Amt'"), total.getMessage());
        assertTrue(notes.stream().noneMatch(n -> "code".equals(n.getField()) || "id".equals(n.getField())));
        // The parser hands the same notes to the validator, and no warning is raised for using a convention.
        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(source);
        assertEquals(3, parser.getAuthoringNotes().size());
        assertTrue(ast.getParserWarnings().isEmpty(), ast.getParserWarnings().toString());
    }

    private String domainModel(String declaredDomainTypes) {
        return """
                {
                  "namespace": "demo", "dslVersion": "3.0.0", "version": "1.0",
                  %s
                  "fieldConventions": [ { "match": "*Email",
                      "defaults": { "type": "string", "maxLength": 120, "domainType": "Email" } } ],
                  "concepts": [ { "name": "Customer", "fields": [ %s, { "name": "contactEmail" },
                      { "name": "billingEmail", "domainType": "Email", "type": "string" } ] } ]
                }
                """.formatted(declaredDomainTypes, ID);
    }

    @Test
    void aConventionCanPointAtADomainTypeSoOneEndingBringsTypeSizeAndValidation() throws Exception {
        Path file = write("model.json", domainModel("\"domainTypes\": [ { \"name\": \"Email\", \"baseType\": \"string\" } ],"));
        var source = new ModelSourceResolver().resolve(file);
        JsonNode contact = field(source.resolvedRoot(), "contactEmail");
        assertEquals("string", contact.get("type").asText());
        assertEquals("Email", contact.get("domainType").asText());
        assertEquals(120, contact.get("maxLength").asInt());
        ModelAst ast = new JsonModelParser().parse(source);
        var errors = new com.npdev.dsl.v1.validation.SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), errors.toString());
        assertTrue(source.diagnostics().stream().anyMatch(
                n -> "contactEmail".equals(n.getField()) && n.getMessage().contains("domainType Email")),
                source.diagnostics().toString());
    }

    @Test
    void aConventionNamingAMissingDomainTypeIsAnErrorNotAGuess() throws Exception {
        Path file = write("model.json", domainModel(""));
        ModelAst ast = new JsonModelParser().parse(new ModelSourceResolver().resolve(file));
        var errors = new com.npdev.dsl.v1.validation.SemanticValidator().validate(ast);
        assertTrue(errors.stream().anyMatch(e -> e.contains("domain type not found: Email")), errors.toString());
    }

    @Test
    void theEndingIsTheLastCamelCaseWordNotAnySubstring() {
        // `hasToRename` ends in the word Rename, `hostname` is one lower-case word, `Dtt` is not `Dt`.
        for (String name : new String[] {"hasToRename", "hostname", "nickname"}) {
            IOException e = assertThrows(IOException.class, () -> resolve(model("", "{ \"name\": \"" + name + "\" }")), name);
            assertTrue(e.getMessage().contains("declares no type"), e.getMessage());
        }
    }

    @Test
    void anExplicitTypeIsNeverTouchedAndAnExplicitSizeWins() throws Exception {
        JsonNode root = resolve(model("", """
                { "name": "ownerName", "type": "integer" },
                { "name": "fullName", "maxLength": 120 }
                """));
        assertEquals("integer", field(root, "ownerName").get("type").asText());
        assertFalse(field(root, "ownerName").has("maxLength"));
        assertEquals("string", field(root, "fullName").get("type").asText());
        assertEquals(120, field(root, "fullName").get("maxLength").asInt());
    }

    @Test
    void theModelsOwnRuleBeatsTheBuiltInAndALongerEndingBeatsAShorterOne() throws Exception {
        JsonNode root = resolve(model("""
                { "match": "*Name", "defaults": { "type": "string", "maxLength": 80 } },
                { "match": "*DueDt", "defaults": { "type": "datetime" } },
                { "match": "*Amt", "defaults": { "type": "decimal", "precision": 18, "scale": 2 } }
                """, """
                { "name": "clientName" }, { "name": "payDueDt" }, { "name": "openDt" }, { "name": "totalAmt" }
                """));
        assertEquals(80, field(root, "clientName").get("maxLength").asInt());
        assertEquals("datetime", field(root, "payDueDt").get("type").asText());
        assertEquals("date", field(root, "openDt").get("type").asText());
        assertEquals(18, field(root, "totalAmt").get("precision").asInt());
    }

    @Test
    void anExactMatchBeatsAnEnding() throws Exception {
        JsonNode root = resolve(model(
                "{ \"match\": \"firstName\", \"defaults\": { \"type\": \"string\", \"maxLength\": 20 } }",
                "{ \"name\": \"firstName\" }, { \"name\": \"lastName\" }"));
        assertEquals(20, field(root, "firstName").get("maxLength").asInt());
        assertEquals(50, field(root, "lastName").get("maxLength").asInt());
    }

    @Test
    void aPackContributesRulesBelowTheModelsOwn() throws Exception {
        write("pack/fin.json", """
                {
                  "dslVersion": "3.0.0", "pack": "fin", "version": "1.0.0",
                  "fieldConventions": [
                    { "match": "*Amt", "defaults": { "type": "decimal", "precision": 12, "scale": 2 } },
                    { "match": "*Name", "defaults": { "type": "string", "maxLength": 200 } }
                  ],
                  "concepts": [ { "name": "Invoice", "fields": [ %s, { "name": "totalAmt" }, { "name": "payerName" } ] } ]
                }
                """.formatted(ID));
        JsonNode root = resolve("""
                {
                  "namespace": "demo", "dslVersion": "3.0.0", "version": "1.0",
                  "packs": [ { "$ref": "pack/fin.json" } ],
                  "fieldConventions": [ { "match": "*Name", "defaults": { "type": "string", "maxLength": 60 } } ],
                  "concepts": [ { "name": "Customer", "fields": [ %s, { "name": "clientName" }, { "name": "creditAmt" } ] } ]
                }
                """.formatted(ID));
        JsonNode fields = root.get("concepts").get(0).get("fields");
        assertEquals(60, fields.get(1).get("maxLength").asInt(), "model rule beats the pack's rule for *Name");
        assertEquals(12, fields.get(2).get("precision").asInt(), "pack rule applies to the model's own fields too");
        JsonNode invoice = root.get("concepts").get(1).get("fields");
        assertEquals(12, invoice.get(1).get("precision").asInt());
        assertEquals(60, invoice.get(2).get("maxLength").asInt());
    }

    @Test
    void aFieldNoRuleMatchesIsAnErrorThatNamesTheFixNeverAGuess() {
        IOException e = assertThrows(IOException.class, () -> resolve(model("", "{ \"name\": \"balance\" }")));
        assertTrue(e.getMessage().contains("'balance'") && e.getMessage().contains("fieldConventions"), e.getMessage());
    }

    @Test
    void badRulesAreRefusedWithThePathOfTheRule() {
        for (String bad : new String[] {
                "{ \"match\": \"*name\", \"defaults\": { \"type\": \"string\" } }",
                "{ \"match\": \"*Name\", \"defaults\": { \"maxLength\": 5 } }",
                "{ \"match\": \"*Name\", \"defaults\": { \"type\": \"string\", \"access\": {} } }"}) {
            assertThrows(IOException.class, () -> resolve(model(bad, "{ \"name\": \"clientName\" }")), bad);
        }
    }

    @Test
    void theExpandedModelParsesAndCompilesLikeAFullyTypedOne() throws Exception {
        Path path = write("model.json", model("", "{ \"name\": \"clientName\" }, { \"name\": \"birthDt\" }"));
        ModelAst ast = new JsonModelParser().parse(path);
        assertEquals(1, ast.getConcepts().size());
        assertEquals(3, ast.getConcepts().get(0).getFields().size());
        assertEquals("string", ast.getConcepts().get(0).getFields().get(1).getType());
        assertEquals("date", ast.getConcepts().get(0).getFields().get(2).getType());
    }
}
