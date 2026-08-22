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
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class InvariantApiIntegrationTest extends AbstractScenarioIntegrationTest {
    private static final String API_KEY = "dev-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDb() {
        deleteAllConceptRows("Provider");
    }

    @Test
    void createMissingRequiredReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "npi", "NPI-MISSING-001"
        ));

        mockMvc.perform(post("/api/providers")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(result -> {
                    int statusCode = result.getResponse().getStatus();
                    org.junit.jupiter.api.Assertions.assertTrue(
                            statusCode == 400 || statusCode == 422,
                            "Expected 400 or 422 for required-field violation, got " + statusCode
                    );
                })
                .andExpect(content().string(containsString("required field 'fullName'")));
    }

    @Test
    void createDuplicateNpiReturns409() throws Exception {
        String first = objectMapper.writeValueAsString(Map.of(
                "fullName", "Dr. Alice",
                "npi", "dup-provider-001"
        ));
        String second = objectMapper.writeValueAsString(Map.of(
                "fullName", "Dr. Bob",
                "npi", "DUP-PROVIDER-001"
        ));

        mockMvc.perform(post("/api/providers")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/providers")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(second))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("unique constraint violated")));
    }

    @Test
    void updateDuplicateNpiReturns409() throws Exception {
        String first = objectMapper.writeValueAsString(Map.of(
                "fullName", "Dr. First",
                "npi", "provider-first-001"
        ));
        String second = objectMapper.writeValueAsString(Map.of(
                "fullName", "Dr. Second",
                "npi", "provider-second-001"
        ));

        JsonNode created1 = createAndParse(first);
        JsonNode created2 = createAndParse(second);

        UUID id2 = UUID.fromString(created2.get("id").asText());
        assertNotNull(created1.get("id"));
        assertNotNull(id2);

        String updateBody = objectMapper.writeValueAsString(Map.of(
                "npi", "PROVIDER-FIRST-001"
        ));

        mockMvc.perform(put("/api/providers/{id}", id2)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("unique constraint violated")));
    }

    private JsonNode createAndParse(String body) throws Exception {
        String response = mockMvc.perform(post("/api/providers")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }
}
