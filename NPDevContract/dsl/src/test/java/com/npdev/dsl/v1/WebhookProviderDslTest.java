package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiled.CompiledWebhook;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** INT-6: provider signature schemes, startFlow and idempotencyKey on inbound webhook doors. */
class WebhookProviderDslTest {

    private static String model(String webhooks) {
        return """
                {
                  "namespace":"demo","dslVersion":"1.0.0","version":"v1",
                  "concepts":[{"name":"Thing","fields":[{"name":"id","type":"uuid","id":true}]}],
                  "events":[{"name":"PaymentConfirmed"}],
                  "flows":[{"name":"RecordPayment","input":{"concept":"Thing","mode":"query"},
                            "steps":[{"name":"r","type":"return","value":"$input"}]}],
                  "webhooks":[
                """ + webhooks + """
                  ]
                }
                """;
    }

    private static final String STRIPE = """
            {"source":"stripe","hmacSecretEnvVar":"STRIPE_SECRET",
             "signature":{"scheme":"stripe-v1","header":"Stripe-Signature","toleranceSeconds":120},
             "idempotencyKey":"id","startFlow":"RecordPayment",
             "fieldMapping":{"orderId":"data.object.metadata.orderId"}}
            """;

    private static final String LEGACY = """
            {"source":"legacy","hmacSecretEnvVar":"LEGACY_SECRET","eventName":"PaymentConfirmed"}
            """;

    @Test
    void parsesCompilesAndRoundTripsProviderDoors() throws Exception {
        ModelAst ast = parse(model(STRIPE + "," + LEGACY));
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors but got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledWebhook stripe = compiled.getWebhooks().stream()
                .filter(w -> w.source().equals("stripe")).findFirst().orElseThrow();
        assertEquals("RecordPayment", stripe.startFlow());
        assertEquals("id", stripe.idempotencyKey());
        assertEquals("stripe-v1", stripe.signature().get("scheme"));
        assertEquals(120, ((Number) stripe.signature().get("toleranceSeconds")).intValue());
        assertEquals("", stripe.eventName());

        String json = CompiledModelCanonicalJson.toJson(compiled);
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(json);
        CompiledWebhook back = restored.getWebhooks().stream()
                .filter(w -> w.source().equals("stripe")).findFirst().orElseThrow();
        assertEquals(stripe.startFlow(), back.startFlow());
        assertEquals(stripe.idempotencyKey(), back.idempotencyKey());
        assertEquals("stripe-v1", back.signature().get("scheme"));
        assertEquals("Stripe-Signature", back.signature().get("header"));
        assertEquals(120, ((Number) back.signature().get("toleranceSeconds")).intValue());
        assertEquals(json, CompiledModelCanonicalJson.toJson(restored));
        assertEquals(stripe.fieldMapping(), back.fieldMapping());
    }

    @Test
    void legacyDoorKeepsItsCanonicalBytes() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model(LEGACY)));
        String json = CompiledModelCanonicalJson.toJson(compiled);
        assertFalse(json.contains("startFlow"), "new keys must be emitted only when declared");
        assertFalse(json.contains("idempotencyKey"));
        assertFalse(json.contains("\"signature\""));
        CompiledWebhook legacy = compiled.getWebhooks().get(0);
        assertEquals("PaymentConfirmed", legacy.eventName());
        assertTrue(legacy.signature().isEmpty());
    }

    @Test
    void doorNeedingNeitherEventNorFlowIsRejectedAtSchemaLayer() {
        IOException error = assertThrows(IOException.class, () -> parse(model(
                "{\"source\":\"x\",\"hmacSecretEnvVar\":\"S\"}")));
        assertTrue(error.getMessage().contains("Model schema validation failed"), error.getMessage());
    }

    @Test
    void unknownSchemeIsRejectedAtSchemaLayer() {
        IOException error = assertThrows(IOException.class, () -> parse(model(
                "{\"source\":\"x\",\"hmacSecretEnvVar\":\"S\",\"startFlow\":\"RecordPayment\","
                        + "\"signature\":{\"scheme\":\"rsa\",\"header\":\"H\"}}")));
        assertTrue(error.getMessage().contains("Model schema validation failed"), error.getMessage());
    }

    @Test
    void semanticRulesFlagUnknownFlowMissingHeaderAndStripePrefix() throws Exception {
        List<String> errors = new SemanticValidator().validate(parse(model(
                "{\"source\":\"a\",\"hmacSecretEnvVar\":\"S\",\"startFlow\":\"Nope\"},"
                        + "{\"source\":\"b\",\"hmacSecretEnvVar\":\"S\",\"startFlow\":\"RecordPayment\","
                        + "\"signature\":{\"scheme\":\"hmac-sha256-hex\"}},"
                        + "{\"source\":\"c\",\"hmacSecretEnvVar\":\"S\",\"startFlow\":\"RecordPayment\","
                        + "\"signature\":{\"scheme\":\"stripe-v1\",\"header\":\"Stripe-Signature\",\"prefix\":\"v1=\"}},"
                        + "{\"source\":\"d\",\"hmacSecretEnvVar\":\"S\",\"startFlow\":\"RecordPayment\","
                        + "\"signature\":{\"scheme\":\"hmac-sha256-hex\",\"header\":\"H\",\"toleranceSeconds\":5}}")));
        String all = String.join("\n", errors);
        assertTrue(all.contains("startFlow references unknown flow Nope"), all);
        assertTrue(all.contains("signature.header is required for scheme hmac-sha256-hex"), all);
        assertTrue(all.contains("signature.prefix is not valid for scheme stripe-v1"), all);
        assertTrue(all.contains("toleranceSeconds only applies to scheme stripe-v1"), all);
    }

    private static ModelAst parse(String json) throws Exception {
        Path file = Files.createTempFile("npdev-webhook-provider-", ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(file);
    }
}
