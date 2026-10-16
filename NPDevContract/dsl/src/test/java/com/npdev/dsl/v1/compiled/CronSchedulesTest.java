package com.npdev.dsl.v1.compiled;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ENG-1: a classic 5-field cron is normalized to Spring's 6-field form at compile time. */
class CronSchedulesTest {

    @Test
    void normalizePrefixesSecondsOnlyForFiveFieldCrons() {
        assertEquals("0 0 2 * * *", CronSchedules.normalize("0 2 * * *"));
        assertEquals("0 15 3 * * *", CronSchedules.normalize("0 15 3 * * *"));
        assertEquals("0 0 2 * * *", CronSchedules.normalize("  0  2 * *  *  "));
        assertEquals("@daily", CronSchedules.normalize("@daily"));
        assertNull(CronSchedules.normalize(null));
        assertEquals(0, CronSchedules.fieldCount(null));
        assertEquals(1, CronSchedules.fieldCount("@daily"));
    }

    @Test
    void fiveFieldCronCompilesToSixFields() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.cron", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "flows": [
                { "name": "Nightly", "concept": "Order",
                  "schedule": { "cron": "0 2 * * *" },
                  "steps": [ { "name": "done", "type": "return", "value": "input" } ] }
              ]
            }
            """;
        ModelAst ast = new JsonModelParser().parse(new ObjectMapper().readTree(json));
        assertTrue(new SemanticValidator().validate(ast).isEmpty());
        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledFlow flow = compiled.getFlows().stream().findFirst().orElseThrow();
        assertEquals("0 0 2 * * *", flow.getSchedule().getCron());
    }
}
