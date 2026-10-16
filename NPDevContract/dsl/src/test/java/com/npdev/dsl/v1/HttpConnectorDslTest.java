package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledCapability;
import com.npdev.dsl.v1.compiled.CompiledCapabilityOperation;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * INT-1: a capability bound to adapter {@code http-connector} carries an {@code http} block (and each
 * operation an {@code http} request). Proves the full chain a new capability key must survive
 * (parser -> resolver -> compiler -> canonical writer -> reader), write-only-when-declared, and the
 * cross-reference rules in HttpConnectorValidation.
 */
class HttpConnectorDslTest {

    private static final String CONNECTOR_MODEL = """
            {
              "namespace":"demo",
              "dslVersion":"1.0.0",
              "version":"v1",
              "concepts":[{"name":"Thing","fields":[{"name":"id","type":"uuid","id":true}]}],
              "propertyScopes":[{"name":"tenant"}],
              "properties":[
                {"name":"paymentsBaseUrl","type":"string","default":"https://api.payments.example/v1","settableAt":["tenant"],"label":"Payments API base URL"}
              ],
              "capabilities":[
                {
                  "name":"payments",
                  "type":"HttpConnector",
                  "http":{
                    "baseUrl":"https://api.payments.example/v1",
                    "baseUrlProperty":"paymentsBaseUrl",
                    "auth":{"type":"bearer","secretEnvVar":"NPDEV_CONNECTOR_PAYMENTS_TOKEN"},
                    "headers":{"Accept":"application/json"},
                    "timeoutMs":10000,
                    "maxRetries":1
                  },
                  "operations":[
                    {
                      "name":"getCustomer",
                      "sideEffects":"reads",
                      "policy":{"retryCount":2,"retryDelayMs":500},
                      "http":{"method":"GET","path":"/customers/{customerId}","params":["customerId"],"response":{"pointer":"/data"}}
                    },
                    {
                      "name":"createCharge",
                      "sideEffects":"external",
                      "http":{
                        "method":"POST",
                        "path":"/charges",
                        "params":["amount","currency","customerId"],
                        "query":{"expand":"customer"},
                        "bodyEncoding":"json",
                        "body":{"amount":"{amount}","currency":"{currency}","customer":{"id":"{customerId}"}},
                        "response":{"pointer":"/id"}
                      }
                    }
                  ]
                }
              ],
              "bindings":[
                {"capability":"payments","adapter":"http-connector"}
              ]
            }
            """;

    private static final String PLAIN_MODEL = """
            {
              "namespace":"demo",
              "dslVersion":"1.0.0",
              "version":"v1",
              "concepts":[{"name":"Thing","fields":[{"name":"id","type":"uuid","id":true}]}],
              "capabilities":[
                {"name":"persistence","type":"PersistenceCapability","operations":["save"]}
              ],
              "bindings":[
                {"capability":"persistence","adapter":"inmemory"}
              ]
            }
            """;

    @Test
    void parsesCompilesAndExposesHttpBlocks() throws Exception {
        ModelAst ast = parse(CONNECTOR_MODEL);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors but got: " + errors);

        assertConnector(new ModelCompiler().compile(ast));
    }

    @Test
    void canonicalJsonRoundTripKeepsHttpMapsIncludingParamOrderAndBodyNesting() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(CONNECTOR_MODEL));
        String json = CompiledModelCanonicalJson.toJson(compiled);
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(json);

        assertConnector(restored);
        assertEquals(json, CompiledModelCanonicalJson.toJson(restored), "canonical JSON must be idempotent");
    }

    @Test
    void modelWithoutHttpEmitsNoHttpKey() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(PLAIN_MODEL));
        String json = CompiledModelCanonicalJson.toJson(compiled);
        assertFalse(json.contains("\"http\""), "byte-identity guard: no http key unless declared");
        assertTrue(capability(compiled, "persistence").getHttp().isEmpty());
    }

    @Test
    void specializationAddingAnOperationInheritsBaseHttpBlock() throws Exception {
        String model = CONNECTOR_MODEL
                .replace("\"bindings\":[", "\"bindings\":[{\"capability\":\"payments2\",\"adapter\":\"http-connector\"},")
                .replace("\"capabilities\":[", """
                        "capabilities":[
                          {
                            "name":"payments2",
                            "type":"HttpConnector",
                            "specializes":"payments",
                            "operations":[
                              {"name":"refund","sideEffects":"external","http":{"method":"POST","path":"/refunds","params":["chargeId"],"body":{"charge":"{chargeId}"}}}
                            ]
                          },""");
        CompiledModel compiled = new ModelCompiler().compile(parse(model));
        CompiledCapability special = capability(compiled, "payments2");
        assertEquals("https://api.payments.example/v1", special.getHttp().get("baseUrl"));
        assertEquals("POST", operation(special, "refund").getHttp().get("method"));
        assertEquals("GET", operation(special, "getCustomer").getHttp().get("method"));
    }

    @Test
    void rejectsPlaceholderNotListedInParams() throws Exception {
        List<String> errors = validate(CONNECTOR_MODEL.replace("\"params\":[\"customerId\"]", "\"params\":[]"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("{customerId}") && e.contains("getCustomer")), errors.toString());
    }

    @Test
    void rejectsBearerWithoutSecretEnvVar() throws Exception {
        List<String> errors = validate(CONNECTOR_MODEL
                .replace("\"auth\":{\"type\":\"bearer\",\"secretEnvVar\":\"NPDEV_CONNECTOR_PAYMENTS_TOKEN\"}",
                        "\"auth\":{\"type\":\"bearer\"}"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("secretEnvVar")), errors.toString());
    }

    @Test
    void rejectsHttpBlockOnCapabilityNotBoundToHttpConnector() throws Exception {
        List<String> errors = validate(CONNECTOR_MODEL.replace("\"adapter\":\"http-connector\"", "\"adapter\":\"repository\""));
        assertTrue(errors.stream().anyMatch(e -> e.contains("inert")), errors.toString());
    }

    @Test
    void rejectsConnectorBindingWithoutBaseUrl() throws Exception {
        List<String> errors = validate(CONNECTOR_MODEL
                .replace("\"baseUrl\":\"https://api.payments.example/v1\",", "")
                .replace("\"baseUrlProperty\":\"paymentsBaseUrl\",", ""));
        assertTrue(errors.stream().anyMatch(e -> e.contains("neither baseUrl nor baseUrlProperty")), errors.toString());
    }

    @Test
    void rejectsBaseUrlPropertyThatIsNotDeclared() throws Exception {
        List<String> errors = validate(CONNECTOR_MODEL.replace("\"baseUrlProperty\":\"paymentsBaseUrl\"", "\"baseUrlProperty\":\"nope\""));
        assertTrue(errors.stream().anyMatch(e -> e.contains("nope")), errors.toString());
    }

    @Test
    void rejectsStringFormOperationOnConnector() throws Exception {
        List<String> errors = validate(CONNECTOR_MODEL.replace("\"operations\":[", "\"operations\":[\"ping\","));
        assertTrue(errors.stream().anyMatch(e -> e.contains("ping") && e.contains("http")), errors.toString());
    }

    @Test
    void schemaRejectsUnknownHttpKeyAndBadMethod() {
        IOException unknown = assertThrows(IOException.class, () -> parse(CONNECTOR_MODEL.replace("\"maxRetries\":1", "\"retries\":1")));
        assertTrue(unknown.getMessage().contains("Model schema validation failed"));
        IOException badMethod = assertThrows(IOException.class, () -> parse(CONNECTOR_MODEL.replace("\"method\":\"GET\"", "\"method\":\"FETCH\"")));
        assertTrue(badMethod.getMessage().contains("Model schema validation failed"));
    }

    private static void assertConnector(CompiledModel compiled) {
        CompiledCapability payments = capability(compiled, "payments");
        assertEquals("https://api.payments.example/v1", payments.getHttp().get("baseUrl"));
        assertEquals("paymentsBaseUrl", payments.getHttp().get("baseUrlProperty"));
        assertEquals(10000, ((Number) payments.getHttp().get("timeoutMs")).intValue());
        assertEquals(Map.of("type", "bearer", "secretEnvVar", "NPDEV_CONNECTOR_PAYMENTS_TOKEN"), payments.getHttp().get("auth"));
        Map<String, Object> charge = operation(payments, "createCharge").getHttp();
        assertEquals("POST", charge.get("method"));
        assertEquals(List.of("amount", "currency", "customerId"), charge.get("params"));
        assertEquals(Map.of("amount", "{amount}", "currency", "{currency}", "customer", Map.of("id", "{customerId}")), charge.get("body"));
        assertEquals(Map.of("pointer", "/id"), charge.get("response"));
        assertEquals("/customers/{customerId}", operation(payments, "getCustomer").getHttp().get("path"));
    }

    private static CompiledCapability capability(CompiledModel model, String name) {
        return model.getCapabilities().stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
    }

    private static CompiledCapabilityOperation operation(CompiledCapability capability, String name) {
        return capability.getOperations().stream().filter(o -> o.getName().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> validate(String json) throws Exception {
        return new SemanticValidator().validate(parse(json));
    }

    private static ModelAst parse(String json) throws IOException {
        Path model = Files.createTempFile("npdev-http-connector-", ".json");
        Files.writeString(model, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(model);
    }
}
