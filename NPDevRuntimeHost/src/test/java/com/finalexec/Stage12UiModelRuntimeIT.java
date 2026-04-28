package com.finalexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class Stage12UiModelRuntimeIT extends AbstractScenarioIntegrationTest {
    private static final String API_KEY = "dev-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void uiModel_endpoint_returns_model_derived_field_metadata() throws Exception {
        String body = mockMvc.perform(get("/api/admin/ui-model")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        JsonNode concepts = root.path("concepts");
        assertThat(concepts.isArray()).isTrue();
        assertThat(concepts).hasSizeGreaterThanOrEqualTo(2);
        assertThat(findConcept(concepts, "Patient")).isNotNull();
        assertThat(findConcept(concepts, "Appointment")).isNotNull();

        int fieldsWithUi = countFieldsWithUiMetadata(concepts);
        assertThat(fieldsWithUi).isGreaterThanOrEqualTo(6);
    }

    @Test
    void modelExport_endpoint_returns_flow_definitions() throws Exception {
        String body = mockMvc.perform(get("/api/admin/model/export")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        JsonNode entities = root.path("concepts");
        JsonNode flows = root.path("flows");

        assertThat(entities.isArray()).isTrue();
        assertThat(entities).hasSizeGreaterThanOrEqualTo(2);
        assertThat(flows.isArray()).isTrue();
        assertThat(flows).hasSizeGreaterThanOrEqualTo(1);
        assertThat(containsNamedNode(flows, "ScheduleAppointmentRunnerV2")).isTrue();
    }

    @Test
    void editorDraft_endpoint_is_reachable() throws Exception {
        String body = mockMvc.perform(get("/api/admin/model/editor/draft")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        assertThat(root.has("concepts")).isTrue();
        assertThat(root.has("flows")).isTrue();
    }

    private static JsonNode findConcept(JsonNode concepts, String conceptName) {
        if (!concepts.isArray()) {
            return null;
        }
        for (JsonNode concept : concepts) {
            if (conceptName.equals(concept.path("name").asText())) {
                return concept;
            }
        }
        return null;
    }

    private static int countFieldsWithUiMetadata(JsonNode concepts) {
        int count = 0;
        if (!concepts.isArray()) {
            return count;
        }
        for (JsonNode concept : concepts) {
            JsonNode fields = concept.path("fields");
            if (!fields.isArray()) {
                continue;
            }
            for (JsonNode field : fields) {
                JsonNode ui = field.path("ui");
                if (ui.isObject() && ui.size() > 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean containsNamedNode(JsonNode nodes, String expectedName) {
        if (!nodes.isArray()) {
            return false;
        }
        for (JsonNode node : nodes) {
            if (expectedName.equals(node.path("name").asText())) {
                return true;
            }
        }
        return false;
    }
}

