package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A1: {@code procedures[].runAs: "system"} -- parsed, compiled, round-tripped, and validated. */
class ProcedureRunAsValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String procedureExtras) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.qrunas", "version": "1.0",
              "concepts": [
                { "name": "Driver", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "status", "type": "string" } ] }
              ],
              "procedures": [
                { "name": "HoldDriver", %s
                  "steps": [ { "name": "busy", "type": "patchConcept", "concept": "Driver", "id": "$input.id",
                               "set": { "status": "BUSY" }, "target": "driver" } ] }
              ]
            }
            """.formatted(procedureExtras);
    }

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    @Test
    void systemRunAsIsAcceptedAndSurvivesCompileAndCanonicalRoundTrip() throws Exception {
        ModelAst ast = parse(modelJson("\"runAs\": \"system\","));
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.stream().noneMatch(e -> e.contains("runAs")), "unexpected errors: " + errors);
        assertEquals("system", ast.getProcedures().get(0).runAs());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals("system", compiled.getProcedures().get(0).runAs());
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        assertEquals("system", restored.getProcedures().get(0).runAs());
    }

    @Test
    void absentRunAsStaysNullEverywhere() throws Exception {
        ModelAst ast = parse(modelJson(""));
        assertNull(ast.getProcedures().get(0).runAs());
        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        assertNull(restored.getProcedures().get(0).runAs());
    }

    @Test
    void anyOtherValueIsRefusedBySchema() {
        ModelSchemaValidationException e = org.junit.jupiter.api.Assertions.assertThrows(
                ModelSchemaValidationException.class, () -> parse(modelJson("\"runAs\": \"admin\",")));
        assertTrue(e.getMessage().contains("runAs"), e.getMessage());
    }

    @Test
    void anElevatedProcedureCannotBeExposedAsAnAction() throws Exception {
        List<String> errors = new SemanticValidator().validate(parse(modelJson(
                "\"runAs\": \"system\", \"actionDescriptor\": { \"actionName\": \"holdDriver\" },")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("cannot be exposed as an action")), "errors: " + errors);
    }
}
