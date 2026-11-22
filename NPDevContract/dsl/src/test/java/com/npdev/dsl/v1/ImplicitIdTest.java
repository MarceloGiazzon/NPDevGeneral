package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.EntityAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Implicit id (owner 2026-11-22): a concept that declares no id field gets {"name":"id","type":"uuid","id":true,
 * "required":true} as its first field -- exactly what every concept in the corpus wrote by hand -- so the compiled
 * model is byte-for-byte the same with or without that line.
 */
class ImplicitIdTest {

    private static final String EXPLICIT_ID = "{\"name\":\"id\",\"type\":\"uuid\",\"id\":true,\"required\":true},";

    private static String model(String idLine) {
        return """
                {"namespace": "demo.implicit", "dslVersion": "3.0.0", "version": "1.0",
                 "concepts": [{"name": "Room", "fields": [""" + idLine + """
                     {"name": "name", "type": "string", "required": true}]}]}
                """;
    }

    private static ModelAst parse(String json) throws Exception {
        Path file = Files.createTempFile("npdev-implicit-id-", ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(file);
    }

    private static List<String> idFieldNames(EntityAst concept) {
        return concept.getFields().stream().filter(FieldAst::isId).map(FieldAst::getName).toList();
    }

    @Test
    void theCompiledModelIsIdenticalWithAndWithoutTheHandWrittenIdLine() throws Exception {
        ModelAst withId = parse(model(EXPLICIT_ID));
        ModelAst without = parse(model(""));
        assertTrue(new SemanticValidator().validate(without).isEmpty(), new SemanticValidator().validate(without).toString());
        assertEquals(
                CompiledModelCanonicalJson.toJson(new ModelCompiler().compile(withId)),
                CompiledModelCanonicalJson.toJson(new ModelCompiler().compile(without)));
    }

    @Test
    void theImplicitIdIsReportedAsOneInfoNoteAndAHandWrittenOneIsNot() throws Exception {
        Path file = Files.createTempFile("npdev-implicit-id-", ".json");
        Files.writeString(file, model(""), StandardCharsets.UTF_8);
        JsonModelParser parser = new JsonModelParser();
        parser.parse(file);
        assertEquals(1, parser.getAuthoringNotes().size());
        var note = parser.getAuthoringNotes().get(0);
        assertEquals("IMPLICIT_ID_ADDED", note.getCode());
        assertEquals(com.npdev.dsl.v1.validation.ValidationSeverity.INFO, note.getSeverity());
        assertTrue(note.getMessage().contains("Room"), note.getMessage());

        Files.writeString(file, model(EXPLICIT_ID), StandardCharsets.UTF_8);
        parser.parse(file);
        assertTrue(parser.getAuthoringNotes().isEmpty(), "the notes describe the LAST parse only");
    }

    @Test
    void theImplicitIdIsTheFirstFieldAndTheOnlyIdField() throws Exception {
        EntityAst room = parse(model("")).getEntities().get(0);
        assertEquals("id", room.getFields().get(0).getName());
        assertEquals("uuid", room.getFields().get(0).getType());
        assertEquals(List.of("id"), idFieldNames(room));
        assertTrue(room.getFields().get(0).isRequired());
    }

    @Test
    void anAuthorsOwnIdFieldIsLeftAlone() throws Exception {
        EntityAst room = parse(model("{\"name\":\"code\",\"type\":\"string\",\"id\":true,\"required\":true},")).getEntities().get(0);
        assertEquals(List.of("code"), idFieldNames(room));
        assertEquals(2, room.getFields().size());
    }

    @Test
    void aFieldCalledIdIsNotDuplicated() throws Exception {
        EntityAst room = parse(model("{\"name\":\"id\",\"type\":\"string\",\"required\":true},")).getEntities().get(0);
        assertEquals(2, room.getFields().size());
    }

    @Test
    void aSpecializingConceptInheritsItsParentsIdInsteadOfGettingASecondOne() throws Exception {
        String json = """
                {"namespace": "demo.implicit", "dslVersion": "3.0.0", "version": "1.0",
                 "concepts": [
                   {"name": "Room", "fields": [{"name": "name", "type": "string", "required": true}]},
                   {"name": "Suite", "specializes": "Room", "fields": [{"name": "butler", "type": "string"}]}]}
                """;
        ModelAst ast = parse(json);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), errors.toString());
        EntityAst suite = ast.getEntities().stream().filter(c -> c.getName().equals("Suite")).findFirst().orElseThrow();
        assertFalse(suite.getFields().stream().anyMatch(FieldAst::isId) && suite.getFields().stream().filter(FieldAst::isId).count() > 1);
    }
}
