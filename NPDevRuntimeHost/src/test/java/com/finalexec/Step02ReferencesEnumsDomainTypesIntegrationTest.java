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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class Step02ReferencesEnumsDomainTypesIntegrationTest extends AbstractScenarioIntegrationTest {
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
    void modelExportContainsCoreSchedulingConcepts() throws Exception {
        String model = mockMvc.perform(get("/api/admin/model/export")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode entities = objectMapper.readTree(model).path("concepts");
        assertTrue(containsEntity(entities, "Patient"));
        assertTrue(containsEntity(entities, "Provider"));
        assertTrue(containsEntity(entities, "ExamRoom"));
        assertTrue(containsEntity(entities, "Appointment"));
    }

    @Test
    void providerUniqueConstraintIsEnforced() throws Exception {
        String firstProvider = objectMapper.writeValueAsString(Map.of(
                "npi", "NPI-10001",
                "fullName", "Dr. Alice Smith",
                "specialty", "Family Medicine"
        ));
        String duplicateProvider = objectMapper.writeValueAsString(Map.of(
                "npi", "NPI-10001",
                "fullName", "Dr. Bob Smith"
        ));

        mockMvc.perform(post("/api/providers")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstProvider))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/providers")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateProvider))
                .andExpect(result -> {
                    int statusCode = result.getResponse().getStatus();
                    assertTrue(statusCode == 409 || statusCode == 422,
                            "Expected 409 or 422 for duplicate NPI, got " + statusCode);
                });
    }

    @Test
    void patientDateValidationIsEnforced() throws Exception {
        String invalidDate = objectMapper.writeValueAsString(Map.of(
                "mrn", "MRN-10002",
                "firstName", "Bad",
                "lastName", "Date",
                "dateOfBirth", "01-31-1990"
        ));

        mockMvc.perform(post("/api/patients")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidDate))
                .andExpect(result -> {
                    int statusCode = result.getResponse().getStatus();
                    assertTrue(statusCode == 400 || statusCode == 422,
                            "Expected 400 or 422 for invalid date, got " + statusCode);
                });
    }

    @Test
    void appointmentReferenceEnumAndDatetimeValidationAreEnforced() throws Exception {
        UUID providerId = createProvider("NPI-20001", "Dr. Provider");
        UUID patientId = createPatient("MRN-20001", "1985-05-10");
        UUID roomId = createRoom("Step 2 Room", "Consult");

        String invalidReference = objectMapper.writeValueAsString(Map.of(
                "patientId", UUID.randomUUID().toString(),
                "providerId", providerId.toString(),
                "roomId", roomId.toString(),
                "visitType", "FollowUp",
                "scheduledAt", "2026-03-10T10:30:00Z",
                "durationMinutes", 30,
                "requiresPreauth", false,
                "preauthStatus", "NotRequired",
                "status", "Scheduled"
        ));
        String invalidEnum = objectMapper.writeValueAsString(Map.of(
                "patientId", patientId.toString(),
                "providerId", providerId.toString(),
                "roomId", roomId.toString(),
                "visitType", "FollowUp",
                "scheduledAt", "2026-03-10T10:30:00Z",
                "durationMinutes", 30,
                "requiresPreauth", true,
                "preauthStatus", "MaybeLater",
                "status", "Scheduled"
        ));
        String invalidDatetime = objectMapper.writeValueAsString(Map.of(
                "patientId", patientId.toString(),
                "providerId", providerId.toString(),
                "roomId", roomId.toString(),
                "visitType", "FollowUp",
                "scheduledAt", "10/03/2026 10:30",
                "durationMinutes", 30,
                "requiresPreauth", false,
                "preauthStatus", "NotRequired",
                "status", "Scheduled"
        ));
        String validAppointment = objectMapper.writeValueAsString(Map.of(
                "patientId", patientId.toString(),
                "providerId", providerId.toString(),
                "roomId", roomId.toString(),
                "visitType", "FollowUp",
                "scheduledAt", "2026-03-10T10:30:00Z",
                "durationMinutes", 30,
                "requiresPreauth", false,
                "preauthStatus", "NotRequired",
                "status", "Scheduled"
        ));

        mockMvc.perform(post("/api/appointments")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidReference))
                .andExpect(result -> {
                    int statusCode = result.getResponse().getStatus();
                    assertTrue(statusCode == 400 || statusCode == 404 || statusCode == 422,
                            "Expected 400/404/422 for invalid reference, got " + statusCode);
                });

        mockMvc.perform(post("/api/appointments")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidEnum))
                .andExpect(result -> {
                    int statusCode = result.getResponse().getStatus();
                    assertTrue(statusCode == 400 || statusCode == 422,
                            "Expected 400 or 422 for invalid enum, got " + statusCode);
                });

        mockMvc.perform(post("/api/appointments")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidDatetime))
                .andExpect(result -> {
                    int statusCode = result.getResponse().getStatus();
                    assertTrue(statusCode == 400 || statusCode == 422,
                            "Expected 400 or 422 for invalid datetime, got " + statusCode);
                });

        mockMvc.perform(post("/api/appointments")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAppointment))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/appointments")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("Scheduled"))
                .andExpect(jsonPath("$[0].visitType").value("FollowUp"))
                .andExpect(jsonPath("$[0].preauthStatus").value("NotRequired"));
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
        JsonNode json = objectMapper.readTree(response);
        assertNotNull(json.get("id"));
        return UUID.fromString(json.get("id").asText());
    }

    private UUID createRoom(String name, String roomType) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", name,
                "roomType", roomType
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

    private UUID createPatient(String mrn, String dateOfBirth) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "mrn", mrn,
                "firstName", "John",
                "lastName", "Doe",
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
        JsonNode json = objectMapper.readTree(response);
        assertNotNull(json.get("id"));
        return UUID.fromString(json.get("id").asText());
    }

    private static boolean containsEntity(JsonNode entities, String expectedName) {
        for (JsonNode entity : entities) {
            if (expectedName.equals(entity.path("name").asText())) {
                return true;
            }
        }
        return false;
    }
}

