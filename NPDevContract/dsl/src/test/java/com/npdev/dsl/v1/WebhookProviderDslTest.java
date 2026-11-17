package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledFlow;
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

/**
 * FORM P3-T10 (was INT-6): inbound webhook doors are declared on the capability they serve
 * ({@code capabilities[].inbound}); the flow a door starts is declared on the flow
 * ({@code trigger.webhook}). Provider signature schemes, idempotencyKey and the derived defaults.
 */
class WebhookProviderDslTest {

    private static String model(String capabilities, String flowTrigger) {
        return """
                {
                  "namespace":"demo","dslVersion":"3.0.0","version":"v1",
                  "concepts":[{"name":"Thing","fields":[{"name":"id","type":"uuid","id":true}]}],
                  "events":[{"name":"PaymentConfirmed"}],
                  "capabilities":[
                """ + capabilities + """
                  ],
                  "flows":[{"name":"RecordPayment","input":{"concept":"Thing","mode":"query"},
                """ + flowTrigger + """
                            "steps":[{"name":"r","type":"return","value":"$input"}]}]
                }
                """;
    }

    private static final String NO_TRIGGER = "";
    private static final String STRIPE_TRIGGER = "\"trigger\":{\"webhook\":\"stripe\"},";

    private static final String STRIPE = """
            {"name":"stripe","inbound":{"hmacSecretEnvVar":"STRIPE_SECRET",
             "signature":{"scheme":"stripe-v1","toleranceSeconds":120},
             "idempotencyKey":"id",
             "fieldMapping":{"orderId":"data.object.metadata.orderId"}}}
            """;

    private static final String LEGACY = """
            {"name":"legacy","inbound":{"path":"legacy-door","hmacSecretEnvVar":"LEGACY_SECRET","eventName":"PaymentConfirmed"}}
            """;

    @Test
    void parsesCompilesAndRoundTripsProviderDoors() throws Exception {
        ModelAst ast = parse(model(STRIPE + "," + LEGACY, STRIPE_TRIGGER));
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors but got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledWebhook stripe = door(compiled, "stripe");
        assertEquals("", stripe.startFlow(), "the flow a door starts is declared on the flow's trigger");
        assertEquals("id", stripe.idempotencyKey());
        assertEquals("stripe-v1", stripe.signature().get("scheme"));
        assertEquals(120, ((Number) stripe.signature().get("toleranceSeconds")).intValue());
        assertEquals("", stripe.eventName());
        CompiledFlow flow = compiled.getFlows().get(0);
        assertEquals("webhook", flow.getTrigger().kind());
        assertEquals("stripe", flow.getTrigger().webhook());

        String json = CompiledModelCanonicalJson.toJson(compiled);
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(json);
        CompiledWebhook back = door(restored, "stripe");
        assertEquals(stripe.idempotencyKey(), back.idempotencyKey());
        assertEquals("stripe-v1", back.signature().get("scheme"));
        assertEquals(120, ((Number) back.signature().get("toleranceSeconds")).intValue());
        assertEquals(json, CompiledModelCanonicalJson.toJson(restored));
        assertEquals(stripe.fieldMapping(), back.fieldMapping());
    }

    @Test
    void pathAndSecretDefaultByConvention() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model(
                "{\"name\":\"order-paid\",\"inbound\":{\"eventName\":\"PaymentConfirmed\"}}", NO_TRIGGER)));
        CompiledWebhook door = door(compiled, "order-paid");
        assertEquals("NPDEV_WEBHOOK_ORDER_PAID_SECRET", door.hmacSecretEnvVar());
        assertEquals("PaymentConfirmed", door.eventName());
    }

    @Test
    void triggerIsRewrittenToTheDoorPathWhenItDiffersFromTheCapabilityName() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model(
                "{\"name\":\"stripe\",\"inbound\":{\"path\":\"pay-hook\"}}", STRIPE_TRIGGER)));
        assertEquals("pay-hook", door(compiled, "pay-hook").source());
        assertEquals("pay-hook", compiled.getFlows().get(0).getTrigger().webhook(),
                "the runtime keys doors and their flows by URL path");
    }

    @Test
    void legacyDoorKeepsItsCanonicalBytes() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model(LEGACY, NO_TRIGGER)));
        String json = CompiledModelCanonicalJson.toJson(compiled);
        assertFalse(json.contains("startFlow"), "new keys must be emitted only when declared");
        assertFalse(json.contains("idempotencyKey"));
        assertFalse(json.contains("\"signature\""));
        CompiledWebhook legacy = compiled.getWebhooks().get(0);
        assertEquals("legacy-door", legacy.source());
        assertEquals("PaymentConfirmed", legacy.eventName());
        assertTrue(legacy.signature().isEmpty());
    }

    @Test
    void unknownSchemeIsRejectedAtSchemaLayer() {
        IOException error = assertThrows(IOException.class, () -> parse(model(
                "{\"name\":\"x\",\"inbound\":{\"signature\":{\"scheme\":\"rsa\",\"header\":\"H\"}}}", NO_TRIGGER)));
        assertTrue(error.getMessage().contains("Model schema validation failed"), error.getMessage());
    }

    @Test
    void semanticRulesFlagDoorThatDoesNothingMissingHeaderAndStripePrefix() throws Exception {
        List<String> errors = new SemanticValidator().validate(parse(model(
                "{\"name\":\"a\",\"inbound\":{}},"
                        + "{\"name\":\"b\",\"inbound\":{\"eventName\":\"PaymentConfirmed\","
                        + "\"signature\":{\"scheme\":\"hmac-sha256-hex\"}}},"
                        + "{\"name\":\"c\",\"inbound\":{\"eventName\":\"PaymentConfirmed\","
                        + "\"signature\":{\"scheme\":\"stripe-v1\",\"header\":\"Stripe-Signature\",\"prefix\":\"v1=\"}}},"
                        + "{\"name\":\"d\",\"inbound\":{\"eventName\":\"PaymentConfirmed\","
                        + "\"signature\":{\"scheme\":\"hmac-sha256-hex\",\"header\":\"H\",\"toleranceSeconds\":5}}},"
                        + "{\"name\":\"e\",\"inbound\":{\"eventName\":\"Nope\"}}", NO_TRIGGER)));
        String all = String.join("\n", errors);
        assertTrue(all.contains("Capability a inbound: the door does nothing"), all);
        assertTrue(all.contains("signature.header is required for scheme hmac-sha256-hex"), all);
        assertTrue(all.contains("signature.prefix is not valid for scheme stripe-v1"), all);
        assertTrue(all.contains("toleranceSeconds only applies to scheme stripe-v1"), all);
        assertTrue(all.contains("references unknown event Nope"), all);
    }

    @Test
    void triggerMustNameACapabilityWithAnInboundDoor() throws Exception {
        List<String> errors = new SemanticValidator().validate(parse(model(
                "{\"name\":\"plain\"}", STRIPE_TRIGGER)));
        String all = String.join("\n", errors);
        assertTrue(all.contains("trigger.webhook references stripe, which is not a capability with an inbound door"), all);
    }

    @Test
    void atMostOneFlowPerDoor() throws Exception {
        String twoFlows = """
                {
                  "namespace":"demo","dslVersion":"3.0.0","version":"v1",
                  "concepts":[{"name":"Thing","fields":[{"name":"id","type":"uuid","id":true}]}],
                  "capabilities":[{"name":"stripe","inbound":{}}],
                  "flows":[
                    {"name":"A","input":{"concept":"Thing","mode":"query"},"trigger":{"webhook":"stripe"},
                     "steps":[{"name":"r","type":"return","value":"$input"}]},
                    {"name":"B","input":{"concept":"Thing","mode":"query"},"trigger":{"webhook":"stripe"},
                     "steps":[{"name":"r","type":"return","value":"$input"}]}
                  ]
                }
                """;
        String all = String.join("\n", new SemanticValidator().validate(parse(twoFlows)));
        assertTrue(all.contains("2 flows declare trigger.webhook stripe"), all);
    }

    private static CompiledWebhook door(CompiledModel compiled, String source) {
        return compiled.getWebhooks().stream().filter(w -> w.source().equals(source)).findFirst().orElseThrow();
    }

    private static ModelAst parse(String json) throws Exception {
        Path file = Files.createTempFile("npdev-webhook-provider-", ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(file);
    }
}
