package com.finalexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.workspace.WorkspaceMenuSeeder;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AsyncWaitResumeE2EIT extends AbstractScenarioIntegrationTest {
    // resume after scheduled time
    // resume after event
    // resume after manual trigger
    // resume failure with expired token or invalid instance ID
    // concurrent resume attempts should remain idempotent
    private static final String API_KEY = "dev-key";
    private static final Duration EXECUTION_WAIT_TIMEOUT = Duration.ofSeconds(8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // REG-171 follow-up: this test dynamically overrides npdev.compiled-model.path to a narrow
    // async-wait/resume-only fixture that declares no workspace::Menu concept, while the assembled
    // app's classpath (shared with every other RuntimeHost test) still carries
    // npdev-seed/workspace-menu-seed.json. WorkspaceMenuSeeder's @ConditionalOnResource only checks
    // that classpath resource, so its real constructor still runs and hard-fails resolving a table
    // name for a concept this fixture model never declares (REG-160's "should be unreachable" case,
    // reachable here because this test's compiled model and the app's classpath resources
    // legitimately diverge, unlike any real generated app). Irrelevant to what this test verifies,
    // so it's mocked out rather than either weakening REG-160's intentional hard-fail or bloating
    // this fixture with an unrelated concept.
    @MockitoBean
    private WorkspaceMenuSeeder workspaceMenuSeeder;

    // Deliberately does NOT set npdev.storage.mode (unlike most of this base class's other
    // subclasses, which each set it to "jdbc" in their own @DynamicPropertySource method -- see
    // AbstractScenarioIntegrationTest's comment for why that decision lives per-subclass rather than
    // as a shared default with a carve-out). This test swaps npdev.compiled-model.path (below) to a
    // narrow async-wait-resume-only fixture whose "User" concept was never part of canonical-demo's
    // own generated schema -- no "users" table exists under "jdbc" mode for it to write to. Leaving
    // this property unset keeps the app's own baked-in "in-memory" default, which is what this
    // fixture actually needs.
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("npdev.compiled-model.path", AsyncWaitResumeE2EIT::compiledModelPath);
        registry.add("npdev.scheduler.enabled", () -> "true");
        registry.add("npdev.scheduler.tick-millis", () -> "100");
        registry.add("npdev.scheduler.batch-limit", () -> "25");
    }

    @Test
    void waitingExecutionCompletesAfterEventPublicationAndResume() throws Exception {
        String email = "async+" + UUID.randomUUID() + "@example.test";

        JsonNode executeResponse = postJson(
                "/api/v1/flows/TypedHappyPath/execute",
                Map.of(
                        "email", email,
                        "name", "Async Test User",
                        "enabled", true
                ),
                202
        );

        String executionId = executeResponse.path("executionId").asText();
        String correlationId = executeResponse.path("correlationId").asText();
        assertTrue(!executionId.isBlank());
        assertTrue(!correlationId.isBlank());
        assertEquals("WAITING_EVENT", executeResponse.path("status").asText());
        assertEquals("EmailVerified", executeResponse.path("awaitedEventName").asText());
        assertEquals(correlationId, executeResponse.path("awaitedCorrelationId").asText());

        JsonNode waitingExecution = awaitExecutionStatus(executionId, "WAITING_EVENT", Duration.ofSeconds(4));
        assertEquals("EmailVerified", waitingExecution.path("waitingForEventName").asText());
        assertEquals(correlationId, waitingExecution.path("correlationId").asText());

        JsonNode publishResponse = postJson(
                "/api/v1/events/publish",
                Map.of(
                        "eventName", "EmailVerified",
                        "correlationId", correlationId,
                        "payload", Map.of("email", email)
                ),
                202
        );
        assertEquals("EmailVerified", publishResponse.path("eventName").asText());
        assertEquals(correlationId, publishResponse.path("correlationId").asText());

        JsonNode completedExecution = awaitCompletionWithFallbackResume(executionId);
        assertEquals("COMPLETED", completedExecution.path("status").asText());

        JsonNode trace = getJson("/api/v1/traces/" + executionId, 200);
        assertEquals(executionId, trace.path("meta").path("executionId").asText());
        assertEquals(correlationId, trace.path("meta").path("correlationId").asText());
        assertEquals("TypedHappyPath", trace.path("meta").path("flowName").asText());
        assertEquals("OK", trace.path("outcome").asText());

        JsonNode correlationTimeline = getJson("/api/v1/correlations/" + correlationId, 200);
        assertEquals(correlationId, correlationTimeline.path("correlationId").asText());
        assertTrue(arrayContains(correlationTimeline.path("executions"), "executionId", executionId));
        assertTrue(arrayContains(correlationTimeline.path("events"), "eventName", "EmailVerified"));
        assertTrue(arrayContainsValue(correlationTimeline.path("traceExecutionIds"), executionId));
    }

    private JsonNode awaitCompletionWithFallbackResume(String executionId) throws Exception {
        try {
            return awaitExecutionStatus(executionId, "COMPLETED", Duration.ofSeconds(4));
        } catch (ConditionTimeoutException timeout) {
            resumeExecution(executionId);
            return awaitExecutionStatus(executionId, "COMPLETED", EXECUTION_WAIT_TIMEOUT);
        }
    }

    private JsonNode awaitExecutionStatus(String executionId, String expectedStatus, Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(150))
                .untilAsserted(() -> assertEquals(expectedStatus, getExecution(executionId).path("status").asText()));
        return getExecution(executionId);
    }

    private JsonNode getExecution(String executionId) {
        try {
            return getJson("/api/v1/executions/" + executionId, 200);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to fetch execution " + executionId, exception);
        }
    }

    private void resumeExecution(String executionId) throws Exception {
        int statusCode = mockMvc.perform(post("/api/v1/executions/{executionId}/resume", executionId)
                        .header("X-Api-Key", API_KEY))
                .andReturn()
                .getResponse()
                .getStatus();
        assertTrue(
                statusCode == 200 || statusCode == 202 || statusCode == 422,
                "Unexpected resume status: " + statusCode
        );
    }

    private JsonNode postJson(String path, Object body, int expectedStatus) throws Exception {
        String response = mockMvc.perform(post(path)
                        .header("X-Api-Key", API_KEY)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode getJson(String path, int expectedStatus) throws Exception {
        String response = mockMvc.perform(get(path)
                        .header("X-Api-Key", API_KEY)
                        .contentType(APPLICATION_JSON))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private static boolean arrayContains(JsonNode array, String fieldName, String expectedValue) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode item : array) {
            if (expectedValue.equals(item.path(fieldName).asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean arrayContainsValue(JsonNode array, String expectedValue) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode item : array) {
            if (expectedValue.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String compiledModelPath() {
        try {
            URL resource = AsyncWaitResumeE2EIT.class.getClassLoader()
                    .getResource("npdev/async-wait-resume-compiled-model.json");
            if (resource == null) {
                throw new IllegalStateException("Missing async wait/resume compiled model test resource");
            }
            return Path.of(resource.toURI()).toAbsolutePath().normalize().toString();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Unable to resolve async wait/resume compiled model path", exception);
        }
    }
}
