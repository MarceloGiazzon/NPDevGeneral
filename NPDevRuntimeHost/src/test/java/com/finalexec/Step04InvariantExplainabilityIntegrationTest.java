package com.finalexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.generated.repositories.AppointmentRepository;
import com.npdev.generated.repositories.ExamRoomRepository;
import com.npdev.generated.repositories.PatientRepository;
import com.npdev.generated.repositories.ProviderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class Step04InvariantExplainabilityIntegrationTest extends AbstractScenarioIntegrationTest {
    private static final String API_KEY = "dev-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
        appointmentRepository.deleteAll();
        examRoomRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();
    }

    @Test
    void providerOverlapReturnsInvariantNameAndPath() throws Exception {
        UUID providerId = createProvider("NPI-STEP4-001", "Dr. Explain Provider");
        UUID patientA = createPatient("MRN-STEP4-001");
        UUID patientB = createPatient("MRN-STEP4-002");
        UUID roomA = createRoom("Explain Room A");
        UUID roomB = createRoom("Explain Room B");

        createAppointment(patientA, providerId, roomA, "2026-03-12T10:00:00Z", 30);

        String responseBody = createAppointmentRaw(patientB, providerId, roomB, "2026-03-12T10:15:00Z", 30);
        JsonNode body = objectMapper.readTree(responseBody);

        assertTrue("invariant_failed".equals(body.path("code").asText()));
        assertTrue("ProviderNoOverlap".equals(body.path("invariant").asText()));
        assertTrue(body.path("path").asText("").contains("providerId"));
    }

    @Test
    void roomOverlapReturnsInvariantNameAndPath() throws Exception {
        UUID providerA = createProvider("NPI-STEP4-101", "Dr. Explain Room A");
        UUID providerB = createProvider("NPI-STEP4-102", "Dr. Explain Room B");
        UUID patientA = createPatient("MRN-STEP4-101");
        UUID patientB = createPatient("MRN-STEP4-102");
        UUID roomId = createRoom("Explain Shared Room");

        createAppointment(patientA, providerA, roomId, "2026-03-12T11:00:00Z", 30);

        String responseBody = createAppointmentRaw(patientB, providerB, roomId, "2026-03-12T11:15:00Z", 30);
        JsonNode body = objectMapper.readTree(responseBody);

        assertTrue("invariant_failed".equals(body.path("code").asText()));
        assertTrue("RoomNoOverlap".equals(body.path("invariant").asText()));
        assertTrue(body.path("path").asText("").contains("roomId"));
    }

    private UUID createProvider(String npi, String fullName) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "npi", npi,
                "fullName", fullName
        ));
        String response = mockMvc.perform(post("/api/providers")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).path("id").asText());
    }

    private UUID createPatient(String mrn) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "mrn", mrn,
                "firstName", "Explain",
                "lastName", "Patient",
                "dateOfBirth", "1990-01-01"
        ));
        String response = mockMvc.perform(post("/api/patients")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).path("id").asText());
    }

    private UUID createRoom(String name) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
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
        return UUID.fromString(objectMapper.readTree(response).path("id").asText());
    }

    private void createAppointment(
            UUID patientId,
            UUID providerId,
            UUID roomId,
            String scheduledAt,
            int durationMinutes
    ) throws Exception {
        String body = appointmentBody(patientId, providerId, roomId, scheduledAt, durationMinutes);

        mockMvc.perform(post("/api/appointments")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private String createAppointmentRaw(
            UUID patientId,
            UUID providerId,
            UUID roomId,
            String scheduledAt,
            int durationMinutes
    ) throws Exception {
        String body = appointmentBody(patientId, providerId, roomId, scheduledAt, durationMinutes);

        return mockMvc.perform(post("/api/appointments")
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
    }

    private String appointmentBody(
            UUID patientId,
            UUID providerId,
            UUID roomId,
            String scheduledAt,
            int durationMinutes
    ) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
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
    }
}
