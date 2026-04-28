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
class Step05ProviderOverlapIntegrationTest extends AbstractScenarioIntegrationTest {
    private static final String API_KEY = "dev-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private ExamRoomRepository examRoomRepository;

    @BeforeEach
    void cleanDb() {
        appointmentRepository.deleteAll();
        examRoomRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();
    }

    @Test
    void noDoubleBookingProviderInvariantIsEnforced() throws Exception {
        UUID provider1 = createProvider("NPI-STEP5-001", "Dr. Provider One");
        UUID provider2 = createProvider("NPI-STEP5-002", "Dr. Provider Two");
        UUID patient1 = createPatient("MRN-STEP5-001", "Patient", "One", "1990-01-01");
        UUID patient2 = createPatient("MRN-STEP5-002", "Patient", "Two", "1991-02-02");
        UUID roomA = createRoom("Room A");
        UUID roomB = createRoom("Room B");
        UUID roomC = createRoom("Room C");

        createAppointment(patient1, provider1, roomA, "2026-03-10T10:30:00Z", 30, 201);

        String overlapResponse = createAppointmentRaw(patient2, provider1, roomB, "2026-03-10T10:45:00Z", 30);
        JsonNode overlapBody = objectMapper.readTree(overlapResponse);
        assertTrue("invariant_failed".equals(overlapBody.path("code").asText()));
        assertTrue("ProviderNoOverlap".equals(overlapBody.path("invariant").asText()));
        assertTrue(overlapBody.path("path").asText("").contains("providerId"));

        createAppointment(patient2, provider1, roomA, "2026-03-10T11:15:00Z", 30, 201);
        createAppointment(patient2, provider2, roomC, "2026-03-10T10:45:00Z", 30, 201);
    }

    private UUID createProvider(String npi, String fullName) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "npi", npi,
                "fullName", fullName,
                "specialty", "Family Medicine"
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

    private UUID createPatient(
            String mrn,
            String firstName,
            String lastName,
            String dateOfBirth
    ) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "mrn", mrn,
                "firstName", firstName,
                "lastName", lastName,
                "dateOfBirth", dateOfBirth
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
        return UUID.fromString(objectMapper.readTree(response).path("id").asText());
    }

    private void createAppointment(
            UUID patientId,
            UUID providerId,
            UUID roomId,
            String scheduledAt,
            int durationMinutes,
            int expectedStatus
    ) throws Exception {
        String body = appointmentBody(patientId, providerId, roomId, scheduledAt, durationMinutes);

        if (expectedStatus == 201) {
            mockMvc.perform(post("/api/appointments")
                            .header("X-Api-Key", API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
            return;
        }

        mockMvc.perform(post("/api/appointments")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(result -> {
                    int actual = result.getResponse().getStatus();
                    assertTrue(actual == expectedStatus,
                            "Expected status " + expectedStatus + " but got " + actual);
                });
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
                    int actual = result.getResponse().getStatus();
                    assertTrue(actual == 400 || actual == 422,
                            "Expected 400 or 422 for overlapping appointment, got " + actual);
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
    }
}
