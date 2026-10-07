package com.finalexec.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.adapters.audit.inproc.InProcAuditLogStore;
import com.npdev.adapters.externalai.inproc.InProcExternalAiCapabilityAdapter;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.ports.AuditQuery;
import com.npdev.kernel.ports.ExternalAiCapabilityContract;
import com.npdev.kernel.ports.ExternalAiStructuredRequest;
import com.npdev.kernel.ports.ExternalAiStructuredResult;
import com.npdev.kernel.ports.ExternalAiVerdictRecord;
import com.npdev.kernel.properties.PropertyExplanation;
import com.npdev.kernel.properties.PropertyResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P4 (G3): the flow-facing externalAi.generate runner -- egress, limits, template, schema, audit. */
class ExternalAiPromptRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CompiledModel model(String egress) throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.ai", "version": "1.0",
              "concepts": [ { "name": "Cap", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
              "propertyScopes": [ { "name": "user", "from": "$user.id" }, { "name": "tenant" } ],
              "properties": [
                { "name": "aiCalls", "type": "int", "default": 2, "settableAt": ["tenant", "user"] },
                { "name": "aiBudget", "type": "string", "default": "1.00", "settableAt": ["tenant"] } ],
              "externalAi": {
                "egress": "%s", "vendors": ["gemini"],
                "prompts": [ { "name": "IdentifyCap", "vendor": "gemini", "model": "gemini-3.5-flash",
                  "template": "Identify {{cap.name}} ({{colors}}) missing={{nope}}", "image": "photo",
                  "outputSchema": { "type": "object", "required": ["brewery"],
                    "properties": { "brewery": { "type": "string" } } } } ],
                "limits": { "callsPerUserPerDay": "aiCalls", "monthlyCostCapUsd": "aiBudget" }
              }
            }
            """.formatted(egress);
        return new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(json)));
    }

    /** A vendor double that records what it was sent and answers with a canned JSON + usage. */
    private static final class FakeVendor implements ExternalAiCapabilityContract {
        final List<ExternalAiStructuredRequest> requests = new ArrayList<>();
        String answer = "{\"brewery\":\"Branik\"}";
        long inputTokens = 1_000_000;
        long outputTokens = 0;

        @Override
        public ExternalAiStructuredResult generateStructured(ExternalAiStructuredRequest request) {
            requests.add(request);
            return new ExternalAiStructuredResult(request.vendorId(), request.model(), answer, inputTokens, outputTokens);
        }

        @Override
        public ExternalAiVerdictRecord ingestVerdict(String missionId, String vendorId, String verdictJson) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MapPropertyResolver implements PropertyResolver {
        final Map<String, Object> values = new HashMap<>();

        @Override
        public Object resolve(String propertyKey, ExecutionContext context) {
            return values.get(propertyKey);
        }

        @Override
        public PropertyExplanation explain(String propertyKey, ExecutionContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void set(String scopeType, String scopeId, String propertyKey, Object propertyValue, ExecutionContext context) {
            values.put(propertyKey, propertyValue);
        }
    }

    private final InProcAuditLogStore audit = new InProcAuditLogStore();
    private final MapPropertyResolver properties = new MapPropertyResolver();

    private ExternalAiPromptRunner runner(CompiledModel model, ExternalAiCapabilityContract contract) {
        properties.values.putIfAbsent("aiCalls", 2);
        properties.values.putIfAbsent("aiBudget", "1.00");
        return new ExternalAiPromptRunner(() -> model, contract, () -> properties, audit, () -> null,
                Map.of("gemini", new ExternalAiPromptRunner.Price(new BigDecimal("0.30"), new BigDecimal("2.50"))),
                Clock.systemUTC());
    }

    private static CapabilityResult call(ExternalAiPromptRunner runner, String prompt, Map<String, Object> input,
                                         String actor) {
        return runner.invoke(
                new CapabilityCall("externalAi", "ExternalAiCapability", "externalAi", "generate",
                        List.of(prompt, input), "corr-1", null),
                Map.of("tenantId", "t1", "actorId", actor));
    }

    private List<AuditRecord> auditRows() {
        return audit.search(new AuditQuery("t1", null, ExternalAiPromptRunner.AUDIT_ACTION, null, null, null, null, 100, 0));
    }

    @Test
    void rendersTheTemplateCallsTheVendorValidatesAndAuditsWithoutThePrompt() throws Exception {
        FakeVendor vendor = new FakeVendor();
        ExternalAiPromptRunner runner = runner(model("apiEnabled"), vendor);

        CapabilityResult result = call(runner, "IdentifyCap",
                Map.of("cap", Map.of("name", "Branik 10"), "colors", List.of("red", "white")), "tito");

        assertTrue(result.ok(), String.valueOf(result.error()));
        assertEquals(Map.of("brewery", "Branik"), result.value());
        ExternalAiStructuredRequest sent = vendor.requests.get(0);
        assertEquals("Identify Branik 10 ([\"red\",\"white\"]) missing=", sent.prompt());
        assertEquals("gemini", sent.vendorId());
        assertEquals("gemini-3.5-flash", sent.model());
        assertFalse(sent.hasImage(), "no photo in the input means a text-only call");

        AuditRecord row = auditRows().get(0);
        assertEquals("success", row.outcome());
        assertEquals("tito", row.actorId());
        assertEquals("IdentifyCap", row.resourceId());
        assertEquals("0.3", row.meta().get("costUsd"), "1M input tokens at $0.30/M");
        String everything = row.toString();
        assertFalse(everything.contains("Branik"), "neither prompt values nor the answer reach the audit log: " + everything);
    }

    @Test
    void placeholdersFallBackToTheCallingStepState() throws Exception {
        FakeVendor vendor = new FakeVendor();
        ExternalAiPromptRunner runner = runner(model("apiEnabled"), vendor);

        CapabilityResult result = runner.invoke(
                new CapabilityCall("externalAi", "ExternalAiCapability", "externalAi", "generate",
                        List.of("IdentifyCap", Map.of("cap", Map.of("name", "Branik"))), "corr-2", null),
                Map.of("tenantId", "t1", "actorId", "tito", "colors", List.of("green")));

        assertTrue(result.ok(), String.valueOf(result.error()));
        assertEquals("Identify Branik ([\"green\"]) missing=", vendor.requests.get(0).prompt());
    }

    @Test
    void procedureArgsArriveInAlphabeticalKeyOrderAndAreMatchedByType() throws Exception {
        // A procedure's { "prompt": "IdentifyCap", "input": "$input" } is positional in alphabetical
        // key order, so the runner sees [input, prompt] -- found live on Pigmentampas.
        FakeVendor vendor = new FakeVendor();
        ExternalAiPromptRunner runner = runner(model("apiEnabled"), vendor);

        CapabilityResult result = runner.invoke(
                new CapabilityCall("externalAi", "ExternalAiCapability", "externalAi", "generate",
                        List.of(Map.of("cap", Map.of("name", "Branik"), "colors", List.of("red")), "IdentifyCap"),
                        "corr-3", null),
                Map.of("tenantId", "t1", "actorId", "tito"));

        assertTrue(result.ok(), String.valueOf(result.error()));
        assertEquals("Identify Branik ([\"red\"]) missing=", vendor.requests.get(0).prompt());
    }

    @Test
    void anAnswerThatFailsTheOutputSchemaFailsTheStepAndStillCountsAsBillable() throws Exception {
        FakeVendor vendor = new FakeVendor();
        vendor.answer = "{\"colour\":\"red\"}";
        CapabilityResult result = call(runner(model("apiEnabled"), vendor), "IdentifyCap", Map.of(), "tito");

        assertFalse(result.ok());
        assertEquals("EXTERNAL_AI_INVALID_OUTPUT", result.error().code());
        assertEquals("INVALID_AI_OUTPUT", auditRows().get(0).reasonCode());
    }

    @Test
    void theDailyQuotaRefusesTheCallAfterTheLimitWithoutReachingTheVendor() throws Exception {
        FakeVendor vendor = new FakeVendor();
        vendor.inputTokens = 0;
        ExternalAiPromptRunner runner = runner(model("apiEnabled"), vendor);

        assertTrue(call(runner, "IdentifyCap", Map.of(), "tito").ok());
        assertTrue(call(runner, "IdentifyCap", Map.of(), "tito").ok());
        CapabilityResult third = call(runner, "IdentifyCap", Map.of(), "tito");

        assertFalse(third.ok());
        assertEquals("EXTERNAL_AI_QUOTA_EXCEEDED", third.error().code());
        assertEquals(2, vendor.requests.size(), "a refused call never reaches the vendor");
        assertTrue(call(runner, "IdentifyCap", Map.of(), "tavo").ok(), "the quota is per user");
    }

    @Test
    void theMonthlyBudgetRefusesOnceTheTenantsEstimatedSpendReachesTheCap() throws Exception {
        FakeVendor vendor = new FakeVendor();
        vendor.outputTokens = 400_000; // $0.30 + $1.00 = $1.30 per call, over the $1.00 cap after one call
        properties.values.put("aiCalls", 100);
        ExternalAiPromptRunner runner = runner(model("apiEnabled"), vendor);

        assertTrue(call(runner, "IdentifyCap", Map.of(), "tito").ok());
        CapabilityResult second = call(runner, "IdentifyCap", Map.of(), "tavo");

        assertFalse(second.ok());
        assertEquals("EXTERNAL_AI_BUDGET_EXCEEDED", second.error().code());
        assertEquals(1, vendor.requests.size());
    }

    @Test
    void deniedEgressAndUnknownPromptsNeverReachTheVendor() throws Exception {
        FakeVendor vendor = new FakeVendor();
        CapabilityResult denied = call(runner(model("packOnly"), vendor), "IdentifyCap", Map.of(), "tito");
        CapabilityResult unknown = call(runner(model("apiEnabled"), vendor), "Nope", Map.of(), "tito");

        assertEquals("EXTERNAL_AI_EGRESS_DENIED", denied.error().code());
        assertEquals("EXTERNAL_AI_UNKNOWN_PROMPT", unknown.error().code());
        assertTrue(vendor.requests.isEmpty());
        assertEquals(2, auditRows().stream().filter(row -> "denied".equals(row.outcome())).count());
    }

    @Test
    void anImageHandleFromAnotherTenantIsRejectedAndADataUriIsSent() throws Exception {
        FakeVendor vendor = new FakeVendor();
        ExternalAiPromptRunner runner = runner(model("apiEnabled"), vendor);

        CapabilityResult foreign = call(runner, "IdentifyCap",
                Map.of("photo", Map.of("storeId", "fs", "key", "other-tenant/abc")), "tito");
        assertEquals("EXTERNAL_AI_IMAGE_REJECTED", foreign.error().code());

        CapabilityResult inline = call(runner, "IdentifyCap",
                Map.of("photo", "data:image/png;base64,AQID"), "tito");
        assertTrue(inline.ok(), String.valueOf(inline.error()));
        assertEquals("image/png", vendor.requests.get(0).imageMimeType());
        assertEquals(3, vendor.requests.get(0).imageBytes().length);
    }

    @Test
    void theOfflineInprocAdapterRunsTheWholePathWithNoVendor(@TempDir Path packDir) throws Exception {
        CapabilityResult result = call(
                runner(model("apiEnabled"), new InProcExternalAiCapabilityAdapter(packDir)), "IdentifyCap", Map.of(), "tito");

        assertTrue(result.ok(), String.valueOf(result.error()));
        assertEquals(Map.of("brewery", "offline"), result.value());
        assertEquals("0", auditRows().get(0).meta().get("costUsd"));
    }
}
