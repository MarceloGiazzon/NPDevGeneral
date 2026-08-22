package com.finalexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.generated.entities.InsuranceClaim;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptGateways;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CanonicalDemoBusinessE2EIT extends AbstractScenarioIntegrationTest {
    private static final String API_KEY = "dev-key";

    @TestConfiguration
    static class RelaxedConceptGatewayConfig {
        @Bean
        @Primary
        ConceptGateway relaxedConceptGateway() {
            return ConceptGateways.inMemory();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDb() {
        deleteAllConceptRows("InsuranceClaim", "Appointment", "Patient", "Provider");
    }

    @Test
    void canonicalDemoBusinessFlowSchedulesChecksInCompletesAndQueuesClaim() throws Exception {
        UUID patientId = createPatient();
        UUID providerId = createProvider();

        JsonNode scheduledAppointment = createAppointment(
                patientId,
                providerId,
                "2026-03-18T14:00:00Z",
                30
        );
        UUID appointmentId = UUID.fromString(scheduledAppointment.path("id").asText());
        assertEquals("Scheduled", scheduledAppointment.path("status").asText());
        assertEquals(patientId.toString(), scheduledAppointment.path("patientId").asText());
        assertEquals(providerId.toString(), scheduledAppointment.path("providerId").asText());

        JsonNode overlappingAppointmentViolation = createOverlappingAppointment(
                patientId,
                providerId,
                "2026-03-18T14:15:00Z",
                30
        );
        assertEquals("invariant_failed", overlappingAppointmentViolation.path("code").asText());
        assertEquals("ProviderSlotAvailable", overlappingAppointmentViolation.path("invariant").asText());
        assertTrue(overlappingAppointmentViolation.path("path").asText("").contains("providerId"));

        JsonNode checkedInAppointment = updateAppointment(
                appointmentId,
                Map.of(
                        "status", "CheckedIn",
                        "checkInTime", "2026-03-18T13:58:00Z"
                )
        );
        assertEquals("CheckedIn", checkedInAppointment.path("status").asText());
        assertEquals("2026-03-18T13:58:00Z", checkedInAppointment.path("checkInTime").asText());

        JsonNode completedAppointment = updateAppointment(
                appointmentId,
                Map.of(
                        "status", "Completed",
                        "checkOutTime", "2026-03-18T14:32:00Z"
                )
        );
        assertEquals("Completed", completedAppointment.path("status").asText());
        assertEquals("2026-03-18T14:32:00Z", completedAppointment.path("checkOutTime").asText());

        InsuranceClaim claim = awaitClaimForAppointment(appointmentId);
        assertEquals(appointmentId, claim.getAppointmentId());
        assertEquals(patientId, claim.getPatientId());
        assertEquals(providerId, claim.getProviderId());
        assertEquals("Draft", claim.getStatus());

        JsonNode insuranceClaims = listInsuranceClaims();
        assertTrue(insuranceClaims.isArray());
        assertFalse(insuranceClaims.isEmpty());
        JsonNode createdClaim = findClaimByAppointmentId(insuranceClaims, appointmentId);
        assertNotNull(createdClaim);
        assertEquals(appointmentId.toString(), createdClaim.path("appointmentId").asText());
        assertEquals(patientId.toString(), createdClaim.path("patientId").asText());
        assertEquals(providerId.toString(), createdClaim.path("providerId").asText());
        assertEquals("Draft", createdClaim.path("status").asText());

        String runtimeFlowsPayload = mockMvc.perform(get("/api/flows/definitions")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode runtimeFlows = objectMapper.readTree(runtimeFlowsPayload);
        assertTrue(runtimeFlows.isArray());
        assertFalse(runtimeFlows.isEmpty());
        assertTrue(containsFlow(runtimeFlows, "CreateAppointment"));
    }

    private UUID createPatient() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "mrn", "MRN-001",
                "firstName", "Ada",
                "lastName", "Lovelace",
                "emergencyContact", Map.of(
                        "name", "Alan Turing",
                        "relationship", "Friend",
                        "phone", "555-0100",
                        "authorizedForDisclosure", true
                ),
                "allergies", List.of(
                        Map.of(
                                "code", "ALG-PEN",
                                "substance", "Penicillin",
                                "severity", "Moderate",
                                "active", true
                        )
                )
        ));
        String response = mockMvc.perform(post("/api/patients")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        assertNotNull(json.get("id"));
        assertEquals("Penicillin", json.path("allergies").path(0).path("substance").asText());
        return UUID.fromString(json.get("id").asText());
    }

    private UUID createProvider() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "npi", "1234567890",
                "fullName", "Dr. Grace Hopper",
                "specialty", "Internal Medicine"
        ));
        String response = mockMvc.perform(post("/api/providers")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        assertNotNull(json.get("id"));
        return UUID.fromString(json.get("id").asText());
    }

    private JsonNode createAppointment(
            UUID patientId,
            UUID providerId,
            String scheduledAt,
            int durationMinutes
    ) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "patientId", patientId.toString(),
                "providerId", providerId.toString(),
                "scheduledAt", scheduledAt,
                "durationMinutes", durationMinutes,
                "status", "Scheduled",
                "visitReason", "Follow-up"
        ));
        String response = mockMvc.perform(post("/api/appointments")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode createOverlappingAppointment(
            UUID patientId,
            UUID providerId,
            String scheduledAt,
            int durationMinutes
    ) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "patientId", patientId.toString(),
                "providerId", providerId.toString(),
                "scheduledAt", scheduledAt,
                "durationMinutes", durationMinutes,
                "status", "Scheduled",
                "visitReason", "Overlap check"
        ));
        String response = mockMvc.perform(post("/api/appointments")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(result -> {
                    int statusCode = result.getResponse().getStatus();
                    assertTrue(statusCode == 400 || statusCode == 422,
                            "Expected 400 or 422 for overlapping appointment, got " + statusCode);
                })
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode updateAppointment(UUID appointmentId, Map<String, Object> updates) throws Exception {
        String body = objectMapper.writeValueAsString(updates);
        String response = mockMvc.perform(put("/api/appointments/{id}", appointmentId)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private InsuranceClaim awaitClaimForAppointment(UUID appointmentId) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            List<InsuranceClaim> claims = conceptRows("InsuranceClaim").stream()
                    .map(row -> conceptEntity(row, InsuranceClaim.class))
                    .filter(claim -> appointmentId.equals(claim.getAppointmentId()))
                    .toList();
            if (!claims.isEmpty()) {
                return claims.get(0);
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Expected an insurance claim for appointment " + appointmentId);
    }

    private JsonNode listInsuranceClaims() throws Exception {
        String response = mockMvc.perform(get("/api/insuranceclaims")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private static JsonNode findClaimByAppointmentId(JsonNode claims, UUID appointmentId) {
        for (JsonNode claim : claims) {
            if (appointmentId.toString().equals(claim.path("appointmentId").asText())) {
                return claim;
            }
        }
        return null;
    }

    private static boolean containsFlow(JsonNode runtimeFlows, String flowName) {
        for (JsonNode flow : runtimeFlows) {
            if (flowName.equals(flow.path("name").asText())) {
                return true;
            }
        }
        return false;
    }
}
