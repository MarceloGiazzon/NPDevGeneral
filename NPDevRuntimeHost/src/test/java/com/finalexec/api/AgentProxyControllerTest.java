package com.finalexec.api;

import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.ExternalAiCapabilityContract;
import com.npdev.kernel.ports.ExternalAiEgressDeniedException;
import com.npdev.kernel.ports.ExternalAiGenerationRequest;
import com.npdev.kernel.ports.ExternalAiGenerationResult;
import com.npdev.kernel.ports.ExternalAiVendorSummary;
import com.npdev.kernel.ports.ExternalAiVerdictRecord;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Copies {@code SchemaImpactControllerTest}'s harness -- a mocked {@link RuntimeContextService}
 * feeding role sets into a standalone {@link MockMvc} controller -- because the questions here are
 * about the guard and the fail-closed path, neither of which needs a database.
 */
class AgentProxyControllerTest {

    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
    private final List<ExternalAiGenerationRequest> sent = new ArrayList<>();

    private MockMvc mockMvcWith(ExternalAiCapabilityContract contract) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ExternalAiCapabilityContract> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(contract);
        return MockMvcBuilders.standaloneSetup(new AgentProxyController(provider, runtimeContextService)).build();
    }

    private void authenticateAs(String... roles) {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("acme", "tester").withRoles(Set.of(roles)));
    }

    /** Stands in for the http adapter: records what it was asked to send, answers a fixed reply. */
    private ExternalAiCapabilityContract stubContract(List<ExternalAiVendorSummary> vendors) {
        return new ExternalAiCapabilityContract() {
            @Override
            public ExternalAiVerdictRecord ingestVerdict(String missionId, String vendorId, String verdictJson) {
                throw new UnsupportedOperationException("not exercised here");
            }

            @Override
            public List<ExternalAiVendorSummary> configuredVendors() {
                return vendors;
            }

            @Override
            public ExternalAiGenerationResult generateText(ExternalAiGenerationRequest request) {
                sent.add(request);
                return new ExternalAiGenerationResult(
                        request.vendorId(), "claude-opus-5", "here is the change", "{\"content\":[]}");
            }
        };
    }

    @Test
    void unauthenticatedCallersGetTheAuthFilterFailureNotAConfigPayload() throws Exception {
        // RuntimeContextService is what throws for an unauthenticated caller in every auth mode; the
        // controller must ask it BEFORE answering, or /config becomes an anonymous read of which
        // vendors an app has wired.
        when(runtimeContextService.currentContext(any()))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "unauthorized"));

        mockMvcWith(stubContract(List.of())).perform(get("/api/agent-proxy/config"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configReportsNotConfiguredWhenNoProviderBeanIsWired() throws Exception {
        authenticateAs("USER");

        mockMvcWith(null).perform(get("/api/agent-proxy/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.vendors").isEmpty());
    }

    @Test
    void configReportsNotConfiguredWhenVendorsExistButNoKeyIsSet() throws Exception {
        authenticateAs("USER");
        ExternalAiCapabilityContract contract = stubContract(List.of(
                new ExternalAiVendorSummary("anthropic", "claude-opus-5",
                        "NPDEV_EXTERNALAI_ANTHROPIC_API_KEY", false, true)));

        mockMvcWith(contract).perform(get("/api/agent-proxy/config"))
                .andExpect(status().isOk())
                // A wired provider with no key must NOT read as configured -- otherwise the page
                // offers a vendor that denies on first use.
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.vendors[0].id").value("anthropic"))
                .andExpect(jsonPath("$.vendors[0].keyPresent").value(false))
                .andExpect(jsonPath("$.vendors[0].keyEnvVarName").value("NPDEV_EXTERNALAI_ANTHROPIC_API_KEY"));
    }

    @Test
    void configNeverEmitsAFieldNameThePlatformsRedactionWouldSwallow() throws Exception {
        authenticateAs("USER");
        ExternalAiCapabilityContract contract = stubContract(List.of(
                new ExternalAiVendorSummary("anthropic", "claude-opus-5",
                        "NPDEV_EXTERNALAI_ANTHROPIC_API_KEY", true, true)));

        String json = mockMvcWith(contract).perform(get("/api/agent-proxy/config"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        // npdev_monitor.redact() replaces any value whose KEY matches this pattern. A response field
        // named apiKeyEnvVar would survive here and come back "<redacted>" in every log bundle, which
        // is precisely when an operator needs to read it.
        for (String forbidden : List.of("password", "pwd", "secret", "token", "apikey", "api_key",
                "api-key", "authorization", "credential", "privatekey")) {
            assertTrue(!json.toLowerCase(java.util.Locale.ROOT).contains("\"" + forbidden),
                    "config response must not carry a field name matching the redaction pattern: " + forbidden
                            + " -- got " + json);
        }
    }

    @Test
    void generateIsForbiddenForAnAuthenticatedNonSuperUser() throws Exception {
        // ADMIN specifically: in an auth.mode=none app the generated RuntimeContextService grants
        // ADMIN to every anonymous caller, so this is the role that must NOT be enough.
        authenticateAs("USER", "OPERATOR", "ADMIN");

        mockMvcWith(stubContract(List.of())).perform(post("/api/agent-proxy/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"anthropic\",\"prompt\":\"hello\"}"))
                .andExpect(status().isForbidden());
        assertEquals(List.of(), sent, "a forbidden request must not reach the vendor");
    }

    @Test
    void generateRejectsAVendorThatIsNotConfigured() throws Exception {
        authenticateAs("SUPERUSER");
        ExternalAiCapabilityContract contract = stubContract(List.of(
                new ExternalAiVendorSummary("anthropic", "claude-opus-5", "ENV", true, true)));

        // The SSRF guard: a caller picks WHICH configured vendor, never where the request goes.
        mockMvcWith(contract).perform(post("/api/agent-proxy/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"http://attacker.example/v1\",\"prompt\":\"hello\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(List.of(), sent);
    }

    @Test
    void generateReturnsTheDenyReasonWhenTheKeyIsMissing() throws Exception {
        authenticateAs("SUPERUSER");
        ExternalAiCapabilityContract denying = new ExternalAiCapabilityContract() {
            @Override
            public ExternalAiVerdictRecord ingestVerdict(String m, String v, String j) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ExternalAiVendorSummary> configuredVendors() {
                return List.of(new ExternalAiVendorSummary(
                        "anthropic", "claude-opus-5", "NPDEV_EXTERNALAI_ANTHROPIC_API_KEY", false, true));
            }

            @Override
            public ExternalAiGenerationResult generateText(ExternalAiGenerationRequest request) {
                throw new ExternalAiEgressDeniedException("EGRESS_DENIED_NO_API_KEY",
                        "No API key configured (env var NPDEV_EXTERNALAI_ANTHROPIC_API_KEY) for vendor 'anthropic'.");
            }
        };

        mockMvcWith(denying).perform(post("/api/agent-proxy/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"anthropic\",\"prompt\":\"hello\"}"))
                // 409, not 500: nothing is broken -- the app is doing exactly what an unconfigured
                // app should do, and the reason names the variable to set.
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("EGRESS_DENIED_NO_API_KEY"))
                // The reason must survive to the caller. A thrown ResponseStatusException would NOT:
                // Spring Boot's server.error.include-message defaults to `never`, so its message
                // arrives as "" and the page can only say "something went wrong".
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("NPDEV_EXTERNALAI_ANTHROPIC_API_KEY")));
    }

    @Test
    void generateForwardsVendorModelAndEffortAndReturnsTheText() throws Exception {
        authenticateAs("SUPERUSER");
        ExternalAiCapabilityContract contract = stubContract(List.of(
                new ExternalAiVendorSummary("anthropic", "claude-opus-5", "ENV", true, true)));

        mockMvcWith(contract).perform(post("/api/agent-proxy/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"anthropic\",\"model\":\"claude-sonnet-5\","
                                + "\"effort\":\"high\",\"prompt\":\"what should I change?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.text").value("here is the change"));

        assertEquals(1, sent.size());
        assertEquals("anthropic", sent.get(0).vendorId());
        assertEquals("claude-sonnet-5", sent.get(0).model());
        assertEquals("high", sent.get(0).effort());
    }

    @Test
    void generateDropsEffortForAVendorThatDoesNotSupportIt() throws Exception {
        authenticateAs("SUPERUSER");
        ExternalAiCapabilityContract contract = stubContract(List.of(
                new ExternalAiVendorSummary("openai", "gpt-4o-mini", "ENV", true, false)));

        mockMvcWith(contract).perform(post("/api/agent-proxy/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"openai\",\"effort\":\"high\",\"prompt\":\"hi\"}"))
                .andExpect(status().isOk());

        // Dropped, not rejected: a page that remembers an effort selection across a vendor switch
        // should not start failing sends because of it.
        assertEquals(null, sent.get(0).effort());
    }

    @Test
    void generateRefusesAPromptOverTheServerSideCap() throws Exception {
        authenticateAs("SUPERUSER");
        ExternalAiCapabilityContract contract = stubContract(List.of(
                new ExternalAiVendorSummary("anthropic", "claude-opus-5", "ENV", true, true)));
        String huge = "x".repeat(200_001);

        mockMvcWith(contract).perform(post("/api/agent-proxy/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"anthropic\",\"prompt\":\"" + huge + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        assertEquals(List.of(), sent, "the cap must stop the send, not merely report on it afterwards");
    }
}
