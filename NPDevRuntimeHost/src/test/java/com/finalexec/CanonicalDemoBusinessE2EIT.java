package com.finalexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.generated.entities.InsuranceClaim;
import com.npdev.generated.repositories.AppointmentRepository;
import com.npdev.generated.repositories.ExamRoomRepository;
import com.npdev.generated.repositories.InsuranceClaimRepository;
import com.npdev.generated.repositories.PatientRepository;
import com.npdev.generated.repositories.ProviderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InsuranceClaimRepository insuranceClaimRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private ExamRoomRepository examRoomRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @BeforeEach
    void cleanDb() {
        insuranceClaimRepository.deleteAll();
        appointmentRepository.deleteAll();
        examRoomRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();
    }

    @Test
    void canonicalDemoBusinessFlowSchedulesChecksInCompletesAndQueuesClaim() throws Exception {
        UUID patientId = createPatient();
        UUID providerId = createProvider();
        UUID roomId = createRoom("Canonical Demo Room A");
        UUID alternateRoomId = createRoom("Canonical Demo Room B");

        JsonNode scheduledAppointment = createAppointment(
                patientId,
                providerId,
                roomId,
                "2026-03-18T14:00:00Z",
                30
        );
        UUID appointmentId = UUID.fromString(scheduledAppointment.path("id").asText());
        assertEquals("Scheduled", scheduledAppointment.path("status").asText());
        assertEquals("Approved", scheduledAppointment.path("preauthStatus").asText());
        assertEquals(patientId.toString(), scheduledAppointment.path("patientId").asText());
        assertEquals(providerId.toString(), scheduledAppointment.path("providerId").asText());
        assertEquals(roomId.toString(), scheduledAppointment.path("roomId").asText());

        JsonNode overlappingAppointmentViolation = createOverlappingAppointment(
                patientId,
                providerId,
                alternateRoomId,
                "2026-03-18T14:15:00Z",
                30
        );
        assertEquals("invariant_failed", overlappingAppointmentViolation.path("code").asText());
        assertEquals("ProviderNoOverlap", overlappingAppointmentViolation.path("invariant").asText());
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
        assertEquals("Queued", claim.getStatus());

        JsonNode insuranceClaims = listInsuranceClaims();
        assertTrue(insuranceClaims.isArray());
        assertFalse(insuranceClaims.isEmpty());
        JsonNode createdClaim = findClaimByAppointmentId(insuranceClaims, appointmentId);
        assertNotNull(createdClaim);
        assertEquals(appointmentId.toString(), createdClaim.path("appointmentId").asText());
        assertEquals(patientId.toString(), createdClaim.path("patientId").asText());
        assertEquals(providerId.toString(), createdClaim.path("providerId").asText());
        assertEquals("Queued", createdClaim.path("status").asText());

        String runtimeExecutionsPayload = mockMvc.perform(get("/api/runtime/executions")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode runtimeExecutions = objectMapper.readTree(runtimeExecutionsPayload);
        assertEquals("Runtime Topology Explorer", runtimeExecutions.path("surfaceName").asText());
        assertTrue(runtimeExecutions.path("runtimeFlowCount").asInt() > 0);
        assertTrue(containsFlow(runtimeExecutions.path("runtimeFlows"), "ScheduleAppointment"));
    }

    private UUID createPatient() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "mrn", "MRN-CANONICAL-001",
                "firstName", "Ada",
                "lastName", "Lovelace",
                "dateOfBirth", "1985-12-10",
                "phone", "555-0100",
                "email", "ada@example.test",
                "allergies", List.of(
                        Map.of(
                                "allergen", "Penicillin",
                                "severity", "Moderate",
                                "reaction", "Rash"
                        )
                ),
                "insurance", Map.of(
                        "payerName", "Northwind Health",
                        "memberId", "NW-778899",
                        "groupNumber", "G-100",
                        "planType", "PPO",
                        "active", true
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
        assertEquals("Northwind Health", json.path("insurance").path("payerName").asText());
        assertEquals("Penicillin", json.path("allergies").path(0).path("allergen").asText());
        return UUID.fromString(json.get("id").asText());
    }

    private UUID createProvider() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "npi", "NPI-CANONICAL-001",
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

    private UUID createRoom(String name) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "name", name,
                "roomType", "Consult"
        ));
        String response = mockMvc.perform(post("/api/examrooms")
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
            UUID roomId,
            String scheduledAt,
            int durationMinutes
    ) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "patientId", patientId.toString(),
                "providerId", providerId.toString(),
                "roomId", roomId.toString(),
                "visitType", "FollowUp",
                "scheduledAt", scheduledAt,
                "durationMinutes", durationMinutes,
                "requiresPreauth", true,
                "preauthStatus", "Approved",
                "status", "Scheduled"
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
            UUID roomId,
            String scheduledAt,
            int durationMinutes
    ) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "patientId", patientId.toString(),
                "providerId", providerId.toString(),
                "roomId", roomId.toString(),
                "visitType", "FollowUp",
                "scheduledAt", scheduledAt,
                "durationMinutes", durationMinutes,
                "requiresPreauth", false,
                "preauthStatus", "NotRequired",
                "status", "Scheduled"
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
            List<InsuranceClaim> claims = insuranceClaimRepository.findAll().stream()
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
            if (flowName.equals(flow.path("flowName").asText())) {
                return true;
            }
        }
        return false;
    }
}
