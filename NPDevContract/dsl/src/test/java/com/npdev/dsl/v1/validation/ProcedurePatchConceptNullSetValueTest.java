package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledProcedureStep;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-224: found on WmsOffice's Z1-Z6 reset procedure, which needed to clear a reference field
 * back to null via {@code patchConcept.set: {"expedicaoId": null}} before deleting the record it
 * restrict-references. {@code JsonModelParser}/{@code ModelCompiler} both wrapped every step's
 * {@code set}/{@code data}/{@code metadata}/{@code select} map with {@code Map.copyOf}, which
 * throws a bare, contextless {@code NullPointerException} on any null VALUE -- not just a null
 * key -- crashing model parsing outright on a legitimate declaration. This test drives the full
 * parse-then-compile pipeline end to end, the same path {@code :NPDevContract:dsl:validateModel}
 * uses, to prove neither stage still crashes and the null actually survives to the compiled step.
 */
class ProcedurePatchConceptNullSetValueTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL_JSON = """
        {
          "dslVersion": "1.0.0", "namespace": "wms.qpatchnullset", "version": "1.0",
          "concepts": [
            { "name": "Romaneio", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "expedicaoId", "type": "uuid" } ] }
          ],
          "procedures": [
            { "name": "ClearExpedicao", "steps": [
              { "name": "clear", "type": "patchConcept", "concept": "Romaneio",
                "id": "$input.id", "set": { "expedicaoId": null } } ] }
          ]
        }
        """;

    @Test
    void parsingASetFieldWithAnExplicitNullValueDoesNotThrow() {
        ModelAst ast = assertDoesNotThrow(
                () -> new JsonModelParser().parse(MAPPER.readTree(MODEL_JSON)),
                "an explicit JSON null inside patchConcept.set must not crash the parser (REG-224)"
        );

        var step = ast.getProcedures().get(0).steps().get(0);
        assertTrue(step.set().containsKey("expedicaoId"));
        assertNull(step.set().get("expedicaoId"));
    }

    @Test
    void compilingASetFieldWithAnExplicitNullValueDoesNotThrow() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL_JSON));

        CompiledModel compiled = assertDoesNotThrow(
                () -> new ModelCompiler().compile(ast),
                "the compiled-model twin (CompiledProcedureStep) must not re-crash on the same null value one layer down (REG-224)"
        );

        CompiledProcedureStep step = compiled.getProcedures().get(0).steps().get(0);
        assertTrue(step.set().containsKey("expedicaoId"));
        assertNull(step.set().get("expedicaoId"));
    }
}
