package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0009: egress must not be enabled with no vendor configured -- the model-level analogue of
 * ExternalAiCapabilityContract's fail-closed runtime default.
 */
class ExternalAiEgressValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static String modelWithExternalAi(String externalAiJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.externalai", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]%s
            }
            """.formatted(externalAiJson == null ? "" : ", \"externalAi\": " + externalAiJson);
    }

    @Test
    void modelWithNoExternalAiBlockHasNoEgressErrors() throws Exception {
        List<String> errors = validate(modelWithExternalAi(null));
        assertTrue(errors.stream().noneMatch(e -> e.contains("externalAi")),
                "unexpected externalAi error for a model with no externalAi block, got: " + errors);
    }

    @Test
    void deniedEgressWithNoVendorsPasses() throws Exception {
        List<String> errors = validate(modelWithExternalAi("{\"egress\": \"denied\"}"));
        assertTrue(errors.stream().noneMatch(e -> e.contains("externalAi")),
                "unexpected externalAi error for denied egress, got: " + errors);
    }

    @Test
    void apiEnabledEgressWithNoVendorsIsRejected() throws Exception {
        List<String> errors = validate(modelWithExternalAi("{\"egress\": \"apiEnabled\"}"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("externalAi.egress")
                        && e.contains("no vendors are declared")),
                "expected an egress-without-vendors error, got: " + errors);
    }

    @Test
    void packOnlyEgressWithEmptyVendorsListIsRejected() throws Exception {
        List<String> errors = validate(modelWithExternalAi("{\"egress\": \"packOnly\", \"vendors\": []}"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("externalAi.egress")
                        && e.contains("no vendors are declared")),
                "expected an egress-without-vendors error, got: " + errors);
    }

    @Test
    void apiEnabledEgressWithAVendorPasses() throws Exception {
        List<String> errors = validate(modelWithExternalAi(
                "{\"egress\": \"apiEnabled\", \"vendors\": [\"openai\"]}"));
        assertTrue(errors.stream().noneMatch(e -> e.contains("externalAi")),
                "unexpected externalAi error with a vendor configured, got: " + errors);
    }
}
