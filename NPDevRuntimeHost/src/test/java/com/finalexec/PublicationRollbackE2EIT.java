package com.finalexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Tag("integration")
class PublicationRollbackE2EIT extends AbstractScenarioIntegrationTest {
    private static final String API_KEY = "dev-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void governedPublicationChainPublishesThenRollsBackWithCommittedPostgresState() throws Exception {
        String runId = UUID.randomUUID().toString();
        String tenantId = "tenant-publication-e2e-" + runId;
        String requestedBy = "publication-rollback-e2e";
        String draftId = "draft-" + runId;
        String transactionReference = "tx-" + runId;
        String sourceMutationReference = "mutation-" + runId;
        String publicationRollbackReference = "publication-rollback-" + runId;
        String rollbackExecutionReference = "rollback-" + runId;

        JsonNode auditRecord = postJson(
                "/api/v1/admin/source-mutation-audit/record",
                Map.of(
                        "mutationScope", "structural-source-mutation",
                        "mutationReference", sourceMutationReference,
                        "auditEventType", "APPROVAL_RECORDED",
                        "decision", "APPROVED",
                        "requestedBy", requestedBy,
                        "rationale", "Capture auditable approval for the source mutation",
                        "tenantId", tenantId
                ),
                202
        );
        String auditId = auditRecord.path("auditId").asText();
        assertFalse(auditId.isBlank());

        JsonNode approvalRecord = postJson(
                "/api/v1/admin/source-mutation-approval/decision",
                Map.of(
                        "mutationScope", "structural-source-mutation",
                        "mutationReference", sourceMutationReference,
                        "decision", "APPROVED",
                        "requestedBy", requestedBy,
                        "rationale", "Approve the source mutation for publication",
                        "tenantId", tenantId
                ),
                202
        );
        String approvalId = approvalRecord.path("approvalId").asText();

        JsonNode rollbackAnchor = postJson(
                "/api/v1/admin/source-mutation-rollback-anchor/create",
                Map.of(
                        "mutationScope", "structural-source-mutation",
                        "mutationReference", sourceMutationReference,
                        "beforeStateReference", "before-state-" + runId,
                        "requestedBy", requestedBy,
                        "rationale", "Capture rollback anchor for the governed mutation",
                        "tenantId", tenantId
                ),
                202
        );
        String rollbackAnchorId = rollbackAnchor.path("rollbackAnchorId").asText();

        JsonNode structuralMapping = postJson(
                "/api/v1/admin/structural-publication-mapping/map",
                Map.of(
                        "publicationBatchId", "batch-" + runId,
                        "tenantId", tenantId,
                        "requestedBy", requestedBy,
                        "rationale", "Map structural publication layer",
                        "sourceMutationReferences", new String[]{sourceMutationReference},
                        "draftReferences", new String[]{draftId},
                        "includedStructuralScopes", new String[]{"concepts", "flows"},
                        "includedConcepts", new String[]{"Patient", "Appointment"}
                ),
                202
        );
        String structuralMappingId = structuralMapping.path("publicationMappingId").asText();
        assertEquals("RESOLVED", structuralMapping.path("integrityStatus").asText());

        JsonNode semanticMapping = postJson(
                "/api/v1/admin/semantic-publication-mapping",
                Map.of(
                        "mappingScope", "canonical-demo",
                        "mappingReference", "semantic-" + runId,
                        "draftReference", draftId,
                        "semanticMutationReferences", new String[]{sourceMutationReference},
                        "requestedBy", requestedBy,
                        "rationale", "Map semantic publication layer",
                        "tenantId", tenantId
                ),
                202
        );
        String semanticMappingId = semanticMapping.path("publicationMappingId").asText();
        assertEquals("RESOLVED", semanticMapping.path("integrityStatus").asText());

        JsonNode publicationTransaction = postJson(
                "/api/v1/admin/publication-transactions",
                Map.of(
                        "transactionScope", "canonical-demo",
                        "transactionReference", transactionReference,
                        "draftReference", draftId,
                        "structuralMappingReferences", new String[]{structuralMappingId},
                        "semanticMappingReferences", new String[]{semanticMappingId},
                        "approvalReferences", new String[]{approvalId},
                        "rollbackAnchorReferences", new String[]{rollbackAnchorId},
                        "requestedBy", requestedBy,
                        "rationale", "Assemble publication transaction",
                        "tenantId", tenantId
                ),
                202
        );
        String publicationTransactionId = publicationTransaction.path("publicationTransactionId").asText();
        assertEquals("RESOLVED", publicationTransaction.path("integrityStatus").asText());

        JsonNode governedPublication = postJson(
                "/api/v1/admin/publication-executor",
                Map.of(
                        "transactionReference", transactionReference,
                        "draftReference", draftId,
                        "requestedBy", requestedBy,
                        "rationale", "Record governed publication execution",
                        "tenantId", tenantId,
                        "executionMode", "governed-publication-v1"
                ),
                202
        );
        assertEquals("RECORDED_EXECUTION_ATTEMPT", governedPublication.path("executionStatus").asText());

        JsonNode realPublication = postJson(
                "/api/v1/admin/real-publication-executor",
                Map.of(
                        "transactionReference", transactionReference,
                        "requestedBy", requestedBy,
                        "rationale", "Execute authoritative publication",
                        "tenantId", tenantId,
                        "publicationMode", "real-publication-v1"
                ),
                202
        );
        String realPublicationExecutionId = realPublication.path("realPublicationExecutionId").asText();
        assertFalse(realPublicationExecutionId.isBlank());
        assertEquals("PUBLISHED", realPublication.path("publicationStatus").asText());
        assertDbPublicationState(realPublicationExecutionId, "PUBLISHED", "AUTHORITATIVE_PUBLICATION_RECORDED");

        JsonNode rollbackExecution = postJson(
                "/api/v1/admin/rollback-execution",
                Map.of(
                        "rollbackReference", rollbackExecutionReference,
                        "anchorReference", rollbackAnchorId,
                        "transactionReference", transactionReference,
                        "requestedBy", requestedBy,
                        "rationale", "Restore source and regeneration artifacts",
                        "tenantId", tenantId,
                        "rollbackMode", "rollback-execution-v1"
                ),
                202
        );
        assertEquals("ROLLED_BACK_PARTIALLY", rollbackExecution.path("rollbackStatus").asText());

        JsonNode publicationRollback = postJson(
                "/api/v1/admin/publication-rollback",
                Map.of(
                        "rollbackReference", publicationRollbackReference,
                        "transactionReference", transactionReference,
                        "executionReference", realPublicationExecutionId,
                        "requestedBy", requestedBy,
                        "rationale", "Rollback publication state after artifact restoration",
                        "tenantId", tenantId,
                        "rollbackMode", "publication-rollback-v1"
                ),
                202
        );
        assertEquals("ROLLED_BACK", publicationRollback.path("publicationRollbackStatus").asText());
        assertTrue(arrayContains(publicationRollback.path("restoredScopes"), "publication-execution-state"));
        assertTrue(arrayContains(publicationRollback.path("restoredScopes"), "publication-transaction-state"));
        assertDbPublicationState(realPublicationExecutionId, "ROLLED_BACK", "PUBLICATION_STATE_RESTORED");

        JsonNode realPublicationHistory = getJson("/api/v1/admin/real-publication-executor/history", 200);
        JsonNode currentPublication = findHistoryItem(
                realPublicationHistory.path("items"),
                "realPublicationExecutionId",
                realPublicationExecutionId
        );
        assertNotNull(currentPublication);
        assertEquals("ROLLED_BACK", currentPublication.path("publicationStatus").asText());
        assertEquals("PUBLICATION_STATE_RESTORED", currentPublication.path("publicationOutcome").asText());

        JsonNode transactionHistory = getJson("/api/v1/admin/publication-transactions/history", 200);
        JsonNode currentTransaction = findHistoryItem(
                transactionHistory.path("items"),
                "publicationTransactionId",
                publicationTransactionId
        );
        assertNotNull(currentTransaction);
        assertEquals("ROLLED_BACK_PUBLICATION_STATE", currentTransaction.path("transactionStatus").asText());
        assertEquals(publicationRollbackReference, currentTransaction.path("publicationRollbackReference").asText());
    }

    private void assertDbPublicationState(
            String realPublicationExecutionId,
            String expectedStatus,
            String expectedOutcome
    ) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT publication_status, publication_outcome
                FROM npdev_publication_execution
                WHERE publication_execution_id = CAST(? AS uuid)
                """,
                realPublicationExecutionId
        );
        assertEquals(expectedStatus, row.get("publication_status"));
        assertEquals(expectedOutcome, row.get("publication_outcome"));
    }

    private JsonNode postJson(String path, Object body, int expectedStatus) throws Exception {
        String response = mockMvc.perform(post(path)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode getJson(String path, int expectedStatus) throws Exception {
        String response = mockMvc.perform(get(path)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private static JsonNode findHistoryItem(JsonNode items, String fieldName, String expectedValue) {
        if (!items.isArray()) {
            return null;
        }
        for (JsonNode item : items) {
            if (expectedValue.equals(item.path(fieldName).asText())) {
                return item;
            }
        }
        return null;
    }

    private static boolean arrayContains(JsonNode items, String expectedValue) {
        if (!items.isArray()) {
            return false;
        }
        for (JsonNode item : items) {
            if (expectedValue.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }
}
