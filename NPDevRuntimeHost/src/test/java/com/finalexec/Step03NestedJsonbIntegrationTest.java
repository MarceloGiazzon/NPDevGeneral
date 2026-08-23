package com.finalexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

    // See AbstractScenarioIntegrationTest's comment on why this is set per-subclass rather than as a
    // shared default: this test exercises canonical-demo's real Patient table, so it needs the
    // Postgres-backed ConceptStore/persistence-capability path.
    @DynamicPropertySource
    static void registerStorageMode(DynamicPropertyRegistry registry) {
        registry.add("npdev.storage.mode", () -> "jdbc");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDb() {
        deleteAllConceptRows("Patient");
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

        JsonNode emergencyContact = findField(patient.path("fields"), "emergencyContact");
        JsonNode allergies = findField(patient.path("fields"), "allergies");
        assertTrue(emergencyContact != null, "Patient must declare emergencyContact");
        assertTrue(allergies != null, "Patient must declare allergies");
        assertTrue("object".equals(emergencyContact.path("type").asText()), "emergencyContact must be object");
        assertTrue("array".equals(allergies.path("type").asText()), "allergies must be array");
    }

    @Test
    void createAndListPatientRoundTripsNestedFields() throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "mrn", "MRN-NEST-01",
                "firstName", "Nested",
                "lastName", "Patient",
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
                                "severity", "Severe",
                                "active", true
                        ),
                        Map.of(
                                "code", "ALG-LTX",
                                "substance", "Latex",
                                "severity", "Moderate",
                                "active", true
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
        assertTrue("Alan Turing".equals(created.path("emergencyContact").path("name").asText()));
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
        assertTrue(containsPatientWithNestedPayload(listed, "MRN-NEST-01"),
                "Patients list must include nested emergencyContact and allergies");
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
            JsonNode emergencyContact = patient.path("emergencyContact");
            JsonNode allergies = patient.path("allergies");
            return emergencyContact.isObject()
                    && "Alan Turing".equals(emergencyContact.path("name").asText())
                    && allergies.isArray()
                    && allergies.size() == 2;
        }
        return false;
    }
}

