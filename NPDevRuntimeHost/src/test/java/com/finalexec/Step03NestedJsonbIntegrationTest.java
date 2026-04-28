package com.finalexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.generated.repositories.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class Step03NestedJsonbIntegrationTest extends AbstractScenarioIntegrationTest {
    private static final String API_KEY = "dev-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PatientRepository patientRepository;

    @BeforeEach
    void cleanDb() {
        patientRepository.deleteAll();
    }

    @Test
    void modelExportContainsNestedPatientFields() throws Exception {
        String model = mockMvc.perform(get("/api/admin/model/export")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode entities = objectMapper.readTree(model).path("concepts");
        JsonNode patient = findEntity(entities, "Patient");
        assertTrue(patient != null, "Model export must include Patient");

        JsonNode insurance = findField(patient.path("fields"), "insurance");
        JsonNode allergies = findField(patient.path("fields"), "allergies");
        assertTrue(insurance != null, "Patient must declare insurance");
        assertTrue(allergies != null, "Patient must declare allergies");
        assertTrue("object".equals(insurance.path("type").asText()), "insurance must be object");
        assertTrue("array".equals(allergies.path("type").asText()), "allergies must be array");
    }

    @Test
    void createAndListPatientRoundTripsNestedFields() throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "mrn", "MRN-NESTED-1001",
                "firstName", "Nested",
                "lastName", "Patient",
                "dateOfBirth", "1992-03-03",
                "insurance", Map.of(
                        "payerName", "Blue Shield",
                        "memberId", "MEM-1001",
                        "groupNumber", "GROUP-77",
                        "planType", "PPO",
                        "active", true
                ),
                "allergies", List.of(
                        Map.of(
                                "allergen", "Penicillin",
                                "severity", "Severe",
                                "reaction", "Rash"
                        ),
                        Map.of(
                                "allergen", "Latex",
                                "severity", "Moderate",
                                "reaction", "Itching"
                        )
                )
        ));

        String createdResponse = mockMvc.perform(post("/api/patients")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode created = objectMapper.readTree(createdResponse);
        assertTrue("Blue Shield".equals(created.path("insurance").path("payerName").asText()));
        assertTrue(created.path("allergies").isArray());
        assertTrue(created.path("allergies").size() == 2);

        String listedResponse = mockMvc.perform(get("/api/patients")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode listed = objectMapper.readTree(listedResponse);
        assertTrue(listed.isArray(), "Patients list must return an array");
        assertTrue(containsPatientWithNestedPayload(listed, "MRN-NESTED-1001"),
                "Patients list must include nested insurance and allergies");
    }

    private static JsonNode findEntity(JsonNode entities, String entityName) {
        if (entities == null || !entities.isArray()) {
            return null;
        }
        for (JsonNode entity : entities) {
            if (entityName.equals(entity.path("name").asText())) {
                return entity;
            }
        }
        return null;
    }

    private static JsonNode findField(JsonNode fields, String fieldName) {
        if (fields == null || !fields.isArray()) {
            return null;
        }
        for (JsonNode field : fields) {
            if (fieldName.equals(field.path("name").asText())) {
                return field;
            }
        }
        return null;
    }

    private static boolean containsPatientWithNestedPayload(JsonNode patients, String expectedMrn) {
        for (JsonNode patient : patients) {
            if (!expectedMrn.equals(patient.path("mrn").asText())) {
                continue;
            }
            JsonNode insurance = patient.path("insurance");
            JsonNode allergies = patient.path("allergies");
            return insurance.isObject()
                    && "Blue Shield".equals(insurance.path("payerName").asText())
                    && allergies.isArray()
                    && allergies.size() == 2;
        }
        return false;
    }
}

