package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2.3 investigation fixture: a procedure step calling an unknown capability used to be reported
 * TWICE -- once by {@code PackValidation.validateProcedureCapabilityCall} ("capability not found:
 * X") and once more by {@code ReferenceIntegrityValidation}'s REG-185 sweep ("references unknown
 * capability X"), because {@code ReferenceIndex.SITE_PROCEDURE_STEP_CAPABILITY} was missing from
 * that sweep's {@code REPORTED_ELSEWHERE} exclusion set (every sibling procedure-step site --
 * concept, query, procedure, set-field targets -- was already excluded; this one alone was not).
 * One broken reference, one error message.
 */
class SemanticGraphValidationTest {

    @Test
    void unknownCapabilityOnProcedureStepIsReportedExactlyOnce() throws Exception {
        String json = """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Ticket",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ],
                  "procedures": [
                    {
                      "name": "NotifyOnTicket",
                      "steps": [
                        { "name": "notify", "type": "callCapability", "capability": "ghostCapability", "operation": "send" }
                      ]
                    }
                  ]
                }
                """;

        List<String> errors = new SemanticValidator().validate(parseJson(json));
        long capabilityErrors = errors.stream()
                .filter(e -> e.toLowerCase().contains("ghostcapability"))
                .count();
        assertEquals(1, capabilityErrors,
                "unknown capability on a procedure step must be reported exactly once, got: " + errors);
    }

    private static ModelAst parseJson(String json) throws Exception {
        Path modelFile = Files.createTempFile("npdev-semantic-graph-validation-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(modelFile);
    }
}
