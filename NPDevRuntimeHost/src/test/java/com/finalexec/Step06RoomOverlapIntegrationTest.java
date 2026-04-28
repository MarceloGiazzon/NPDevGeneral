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
class Step06RoomOverlapIntegrationTest extends AbstractScenarioIntegrationTest {
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
    void noDoubleBookingRoomInvariantIsEnforced() throws Exception {
        UUID roomA = createRoom("Exam Room A");
        UUID roomB = createRoom("Exam Room B");
        UUID provider1 = createProvider("NPI-STEP6-001", "Dr. Room One");
        UUID provider2 = createProvider("NPI-STEP6-002", "Dr. Room Two");
        UUID provider3 = createProvider("NPI-STEP6-003", "Dr. Room Three");
        UUID patient = createPatient("MRN-STEP6-001", "Room", "Patient", "1990-01-01");

        String missingRoomResponse = createAppointmentRawWithoutRoom(
                patient,
                provider1,
                "2026-03-11T10:00:00Z",
                30
        );
        JsonNode missingRoomBody = objectMapper.readTree(missingRoomResponse);
        assertTrue("invariant_failed".equals(missingRoomBody.path("code").asText()));
        assertTrue(missingRoomBody.path("path").asText("").contains("roomId"));

        createAppointment(
                patient,
                provider1,
                roomA,
                "2026-03-11T10:30:00Z",
                30,
                201
        );

        String overlapResponse = createAppointmentRaw(
                patient,
                provider2,
                roomA,
                "2026-03-11T10:45:00Z",
                30
        );
        JsonNode overlapBody = objectMapper.readTree(overlapResponse);
        assertTrue("invariant_failed".equals(overlapBody.path("code").asText()));
        assertTrue("RoomNoOverlap".equals(overlapBody.path("invariant").asText()));
        assertTrue(overlapBody.path("path").asText("").contains("roomId"));

        createAppointment(
                patient,
                provider2,
                roomA,
                "2026-03-11T11:00:00Z",
                30,
                201
        );

        createAppointment(
                patient,
                provider3,
                roomB,
                "2026-03-11T10:45:00Z",
                30,
                201
        );
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
                            "Expected 400 or 422 for overlapping room appointment, got " + actual);
                })
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String createAppointmentRawWithoutRoom(
            UUID patientId,
            UUID providerId,
            String scheduledAt,
            int durationMinutes
    ) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "patientId", patientId.toString(),
                "providerId", providerId.toString(),
                "visitType", "FollowUp",
                "scheduledAt", scheduledAt,
                "durationMinutes", durationMinutes,
                "requiresPreauth", false,
                "preauthStatus", "NotRequired",
                "status", "Scheduled"
        ));

        return mockMvc.perform(post("/api/appointments")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(result -> {
                    int actual = result.getResponse().getStatus();
                    assertTrue(actual == 400 || actual == 422,
                            "Expected 400 or 422 for missing roomId, got " + actual);
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
