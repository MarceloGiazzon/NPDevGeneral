package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlTypeSupport;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** LIFT-UPLOAD-P2: a `file`-typed field compiles, carries its metadata, and maps to a JSON handle column. */
class FileFieldValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String fileFieldJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.upload", "version": "1.0",
              "concepts": [
                { "name": "Document", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    %s
                  ] }
              ]
            }
            """.formatted(fileFieldJson);
    }

    @Test
    void fileFieldCompilesWithDeclaredMetadata() throws Exception {
        String json = modelJson("""
            { "name": "attachment", "type": "file",
              "file": { "contentTypes": ["application/pdf", "image/png"], "maxSizeBytes": 5242880, "multiple": false } }
            """);
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "unexpected errors: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledField field = compiled.getConcepts().stream()
                .findFirst().orElseThrow().getFields().stream()
                .filter(f -> "attachment".equals(f.getName()))
                .findFirst().orElseThrow();

        assertEquals("file", field.getDslType());
        // HARDEN-OBJSTORE: pins a real bug -- ModelCompiler.toJavaType had no "file" case, so it
        // fell through to the "String" default, mismatching the JSONB column below and breaking
        // entity (de)serialization for any model declaring a file field through real authoring.
        assertEquals("com.fasterxml.jackson.databind.JsonNode", field.getJavaType());
        assertNotNull(field.getFile());
        assertEquals(List.of("application/pdf", "image/png"), field.getFile().contentTypes());
        assertEquals(5242880L, field.getFile().maxSizeBytes());
        assertFalse(field.getFile().multiple());
        assertEquals("JSONB", SqlTypeSupport.sqlType(field));
    }

    @Test
    void fileFieldWithoutMetadataStillCompiles() throws Exception {
        String json = modelJson("{ \"name\": \"attachment\", \"type\": \"file\" }");
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        assertTrue(new SemanticValidator().validate(ast).isEmpty());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledField field = compiled.getConcepts().stream()
                .findFirst().orElseThrow().getFields().stream()
                .filter(f -> "attachment".equals(f.getName()))
                .findFirst().orElseThrow();
        assertNull(field.getFile());
        assertEquals("JSONB", SqlTypeSupport.sqlType(field));
    }

    @Test
    void fileFieldRejectsUniqueAtSchemaLevel() {
        String json = modelJson("{ \"name\": \"attachment\", \"type\": \"file\", \"unique\": true }");
        assertThrows(ModelSchemaValidationException.class, () -> new JsonModelParser().parse(MAPPER.readTree(json)));
    }

    @Test
    void fileFieldRejectsReferenceAtSchemaLevel() {
        String json = modelJson("{ \"name\": \"attachment\", \"type\": \"file\", \"ref\": \"Order\" }");
        assertThrows(ModelSchemaValidationException.class, () -> new JsonModelParser().parse(MAPPER.readTree(json)));
    }
}
