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

    // See AbstractScenarioIntegrationTest's comment on why this is set per-subclass rather than as a
    // shared default: this test exercises canonical-demo's real Provider table, so it needs the
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
        // npi carries domainType NPI (a real 10-digit provider identifier, see model.json) -- these
        // must fit its VARCHAR(10) column, unlike the free-text-length placeholders this test used
        // before that constraint existed.
        String first = objectMapper.writeValueAsString(Map.of(
                "fullName", "Dr. Alice",
                "npi", "1112223334"
        ));
        String second = objectMapper.writeValueAsString(Map.of(
                "fullName", "Dr. Bob",
                "npi", "1112223334"
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
        // Same domainType NPI VARCHAR(10) constraint as createDuplicateNpiReturns409 above.
        String first = objectMapper.writeValueAsString(Map.of(
                "fullName", "Dr. First",
                "npi", "1112223334"
        ));
        String second = objectMapper.writeValueAsString(Map.of(
                "fullName", "Dr. Second",
                "npi", "2223334445"
        ));

        JsonNode created1 = createAndParse(first);
        JsonNode created2 = createAndParse(second);

        UUID id2 = UUID.fromString(created2.get("id").asText());
        assertNotNull(created1.get("id"));
        assertNotNull(id2);

        String updateBody = objectMapper.writeValueAsString(Map.of(
                "npi", "1112223334"
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
