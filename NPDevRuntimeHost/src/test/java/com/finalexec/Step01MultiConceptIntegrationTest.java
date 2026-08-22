package com.finalexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class Step01MultiConceptIntegrationTest extends AbstractScenarioIntegrationTest {
    private static final String API_KEY = "dev-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDb() {
        deleteAllConceptRows("Patient", "Provider");
    }

    @Test
    void modelExportContainsProviderAndPatientConcepts() throws Exception {
        String model = mockMvc.perform(get("/api/admin/model/export")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(model);
        JsonNode entities = root.path("concepts");
        assertTrue(entities.isArray(), "Model export must include entities array");
        assertTrue(containsEntity(entities, "Provider"), "Model export must include Provider concept");
        assertTrue(containsEntity(entities, "Patient"), "Model export must include Patient concept");
    }

    @Test
    void patientCreateAndListEndpointsWork() throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "mrn", "MRN-1001",
                "firstName", "Ana",
                "lastName", "Silva",
                "dateOfBirth", "1988-04-20"
        ));

        mockMvc.perform(post("/api/patients")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/patients")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].firstName").value("Ana"))
                .andExpect(jsonPath("$[0].lastName").value("Silva"))
                .andExpect(jsonPath("$[0].dateOfBirth").value("1988-04-20"));
    }

    @Test
    void patientMissingRequiredFieldReturnsValidationError() throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "mrn", "MRN-2001",
                "firstName", "NoLastName",
                "dateOfBirth", "1990-01-01"
        ));

        mockMvc.perform(post("/api/patients")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(result -> {
                    int statusCode = result.getResponse().getStatus();
                    assertTrue(statusCode == 400 || statusCode == 422,
                            "Expected 400 or 422 for required-field validation, got " + statusCode);
                })
                .andExpect(content().string(org.hamcrest.Matchers.containsString("required field 'lastName'")));
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

