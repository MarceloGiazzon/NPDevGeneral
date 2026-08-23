package com.finalexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Tag("integration")
class TenantIsolationE2EIT extends AbstractScenarioIntegrationTest {
    private static final String TENANT_A_API_KEY = "tenant-a-key";
    private static final String TENANT_B_API_KEY = "tenant-b-key";

    // See AbstractScenarioIntegrationTest's comment on why this is set per-subclass rather than as a
    // shared default. Stays consistent with every other subclass here rather than relying on the
    // app's own "in-memory" default by omission.
    @DynamicPropertySource
    static void registerStorageMode(DynamicPropertyRegistry registry) {
        registry.add("npdev.storage.mode", () -> "jdbc");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void tenantAReadPathCannotSeeTenantBEventPayload() throws Exception {
        String runId = UUID.randomUUID().toString();
        String eventName = "TenantIsolationProbe";
        String correlationId = "tenant-isolation-" + runId;
        String tenantBSecret = "tenant-b-secret-" + runId;

        JsonNode tenantBPublishedEvent = postJson(
                TENANT_B_API_KEY,
                "/api/v1/events/publish",
                Map.of(
                        "eventName", eventName,
                        "correlationId", correlationId,
                        "causationId", "tenant-isolation-e2e-" + runId,
                        "payload", Map.of(
                                "ownerTenant", "tenant-b",
                                "marker", tenantBSecret
                        )
                ),
                202
        );
        String eventId = tenantBPublishedEvent.path("eventId").asText();
        assertFalse(eventId.isBlank());

        JsonNode tenantBStream = getJson(
                TENANT_B_API_KEY,
                "/api/v1/events/by-correlation/" + correlationId,
                200
        );
        assertEquals(1, tenantBStream.size());
        assertEquals(eventId, tenantBStream.get(0).path("eventId").asText());
        assertEquals("tenant-b", tenantBStream.get(0).path("tenantId").asText());

        JsonNode tenantAStream = getJson(
                TENANT_A_API_KEY,
                "/api/v1/events/by-correlation/" + correlationId,
                200
        );
        assertEquals(0, tenantAStream.size());
        assertFalse(tenantAStream.toString().contains(tenantBSecret));

        int crossTenantReadStatus = mockMvc.perform(get("/api/v1/events/{eventId}", eventId)
                        .header("X-Api-Key", TENANT_A_API_KEY))
                .andReturn()
                .getResponse()
                .getStatus();
        assertTrue(
                crossTenantReadStatus == 403 || crossTenantReadStatus == 404,
                "Expected tenant A read of tenant B event to return 403 or 404, got " + crossTenantReadStatus
        );

        JsonNode tenantAByName = getJson(
                TENANT_A_API_KEY,
                "/api/v1/events/by-name/" + eventName,
                200
        );
        assertFalse(tenantAByName.toString().contains(tenantBSecret));
    }

    private JsonNode postJson(String apiKey, String path, Object body, int expectedStatus) throws Exception {
        String response = mockMvc.perform(post(path)
                        .header("X-Api-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode getJson(String apiKey, String path, int expectedStatus) throws Exception {
        String response = mockMvc.perform(get(path)
                        .header("X-Api-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }
}
