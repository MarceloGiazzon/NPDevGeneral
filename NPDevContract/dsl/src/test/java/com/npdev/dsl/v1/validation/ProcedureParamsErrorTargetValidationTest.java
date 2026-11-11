package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiled.CompiledProcedureStep;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** K2 (runQuery params), K3 (capabilityCall errorTarget), K1 (new computeValue operators): validation + round trip. */
class ProcedureParamsErrorTargetValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String model(String steps) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.qparams", "version": "1.0",
              "concepts": [ { "name": "Order", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "customer", "type": "string" } ] } ],
              "capabilities": [ { "name": "psp", "type": "HttpConnector",
                "http": { "baseUrl": "https://psp.example.com" },
                "operations": [ { "name": "charge", "sideEffects": "writes", "http": { "method": "POST", "path": "/charge" } } ] } ],
              "bindings": [ { "capability": "psp", "adapter": "http-connector" } ],
              "queries": [ { "name": "ByCustomer", "concept": "Order", "where": "customer == :who",
                "parameters": [ { "name": "who", "type": "string", "required": true } ] } ],
              "procedures": [ { "name": "P", "steps": [ %s ] } ]
            }
            """.formatted(steps);
    }

    private static List<String> errors(String steps) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(model(steps)));
        return new SemanticValidator().validate(ast);
    }

    @Test
    void wellFormedParamsAndErrorTargetPassAndRoundTrip() throws Exception {
        String steps = """
            { "name": "q", "type": "runQuery", "query": "ByCustomer", "concept": "Order", "params": { "who": "$input.customer" }, "target": "rows" },
            { "name": "c", "type": "capabilityCall", "capability": "psp", "operation": "charge", "args": {}, "target": "charged", "errorTarget": "chargeError" },
            { "name": "m", "type": "computeValue", "operation": "multiply", "left": 2, "right": 3, "target": "x" },
            { "name": "r", "type": "computeValue", "operation": "round", "left": 2.567, "right": 2, "target": "y" }
            """;
        List<String> errs = errors(steps);
        assertTrue(errs.stream().noneMatch(e -> e.contains("params") || e.contains("errorTarget") || e.contains("computeValue")),
                "unexpected: " + errs);
        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(model(steps))));
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        List<CompiledProcedureStep> back = restored.getProcedures().get(0).steps();
        assertEquals("$input.customer", back.get(0).params().get("who"));
        assertEquals("chargeError", back.get(1).errorTarget());
    }

    @Test
    void unknownParamsKeyAndMissingRequiredAreRefused() throws Exception {
        List<String> unknown = errors("""
            { "name": "q", "type": "runQuery", "query": "ByCustomer", "concept": "Order", "params": { "who": "a", "nope": 1 }, "target": "rows" }
            """);
        assertTrue(unknown.stream().anyMatch(e -> e.contains("params.nope is not a declared parameter")), "" + unknown);
        List<String> missing = errors("""
            { "name": "q", "type": "runQuery", "query": "ByCustomer", "concept": "Order", "target": "rows" }
            """);
        assertTrue(missing.stream().anyMatch(e -> e.contains("requires parameter 'who'")), "" + missing);
    }

    @Test
    void paramsAndErrorTargetOnTheWrongStepAreRefused() throws Exception {
        List<String> errs = errors("""
            { "name": "m", "type": "mapValue", "value": "a", "target": "t", "params": { "a": 1 }, "errorTarget": "e" },
            { "name": "c", "type": "capabilityCall", "capability": "psp", "operation": "charge", "args": {}, "target": "same", "errorTarget": "same" }
            """);
        assertTrue(errs.stream().anyMatch(e -> e.contains("params is only valid on a runQuery")), "" + errs);
        assertTrue(errs.stream().anyMatch(e -> e.contains("errorTarget is only valid on a capabilityCall")), "" + errs);
        assertTrue(errs.stream().anyMatch(e -> e.contains("errorTarget must differ from target")), "" + errs);
    }
}
