package com.finalexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.api.internal.PublicationTransactionRecordController;
import com.finalexec.api.internal.RollbackExecutionController;
import com.finalexec.api.internal.SemanticPublicationMappingController;
import com.finalexec.api.internal.SourceMutationApprovalGateController;
import com.finalexec.api.internal.SourceMutationAuditRecordController;
import com.finalexec.api.internal.SourceMutationRollbackAnchorController;
import com.finalexec.api.internal.StructuralPublicationMappingController;
import com.finalexec.npdev.service.internal.CanonicalSourceArtifactStore;
import com.finalexec.npdev.service.PublicationChainReferenceResolver;
import com.finalexec.npdev.service.internal.PublicationTransactionRecordService;
import com.finalexec.npdev.service.internal.RollbackExecutionService;
import com.finalexec.npdev.service.internal.SemanticPublicationMappingService;
import com.finalexec.npdev.service.internal.SourceMutationApprovalGateService;
import com.finalexec.npdev.service.internal.SourceMutationAuditRecordService;
import com.finalexec.npdev.service.internal.SourceMutationRegenerationArtifactStore;
import com.finalexec.npdev.service.internal.SourceMutationRollbackAnchorService;
import com.finalexec.npdev.service.internal.StructuralPublicationMappingService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicationChainTenantReferenceValidationTest {
    private static final String API_KEY = "dev-key";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PublicationChainReferenceResolver referenceResolver = new PublicationChainReferenceResolver(objectMapper);

        SourceMutationApprovalGateService approvalGateService = new SourceMutationApprovalGateService(objectMapper);
        SourceMutationAuditRecordService auditRecordService = new SourceMutationAuditRecordService(objectMapper);
        SourceMutationRollbackAnchorService rollbackAnchorService = new SourceMutationRollbackAnchorService(objectMapper);
        StructuralPublicationMappingService structuralPublicationMappingService =
                new StructuralPublicationMappingService(objectMapper, referenceResolver);
        SemanticPublicationMappingService semanticPublicationMappingService =
                new SemanticPublicationMappingService(objectMapper, referenceResolver);
        PublicationTransactionRecordService publicationTransactionRecordService =
                new PublicationTransactionRecordService(objectMapper, referenceResolver);
        RollbackExecutionService rollbackExecutionService = new RollbackExecutionService(
                objectMapper,
                referenceResolver,
                new CanonicalSourceArtifactStore(objectMapper),
                new SourceMutationRegenerationArtifactStore(objectMapper)
        );

        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("admin-tenant", "admin-user").withRoles(Set.of("ADMIN")));

        mockMvc = MockMvcBuilders.standaloneSetup(
                new SourceMutationApprovalGateController(approvalGateService, runtimeContextService),
                new SourceMutationAuditRecordController(auditRecordService, runtimeContextService),
                new SourceMutationRollbackAnchorController(rollbackAnchorService, runtimeContextService),
                new StructuralPublicationMappingController(structuralPublicationMappingService, runtimeContextService),
                new SemanticPublicationMappingController(semanticPublicationMappingService, runtimeContextService),
                new PublicationTransactionRecordController(publicationTransactionRecordService, runtimeContextService),
                new RollbackExecutionController(rollbackExecutionService, runtimeContextService)
        ).build();
    }

    @Test
    void rollbackExecutionRejectsCrossTenantReferences() throws Exception {
        String runId = UUID.randomUUID().toString();
        String tenantA = "tenant-a-isolation-" + runId;
        String tenantB = "tenant-b-isolation-" + runId;
        String requestedBy = "tenant-isolation-it";
        String draftReference = "draft-" + runId;
        String mutationReference = "mutation-" + runId;
        String transactionReference = "tx-" + runId;
        String rollbackReference = "rollback-" + runId;

        JsonNode auditRecord = postJson(
                "/api/v1/admin/source-mutation-audit/record",
                Map.of(
                        "mutationScope", "structural-source-mutation",
                        "mutationReference", mutationReference,
                        "auditEventType", "APPROVAL_RECORDED",
                        "decision", "APPROVED",
                        "requestedBy", requestedBy,
                        "rationale", "Record tenant A audit evidence",
                        "tenantId", tenantA
                ),
                202
        );
        String auditId = auditRecord.path("auditId").asText();
        assertFalse(auditId.isBlank());

        JsonNode approvalRecord = postJson(
                "/api/v1/admin/source-mutation-approval/decision",
                Map.of(
                        "mutationScope", "structural-source-mutation",
                        "mutationReference", mutationReference,
                        "decision", "APPROVED",
                        "requestedBy", requestedBy,
                        "rationale", "Approve tenant A mutation",
                        "tenantId", tenantA
                ),
                202
        );
        String approvalId = approvalRecord.path("approvalId").asText();
        assertFalse(approvalId.isBlank());

        JsonNode rollbackAnchor = postJson(
                "/api/v1/admin/source-mutation-rollback-anchor/create",
                Map.of(
                        "mutationScope", "structural-source-mutation",
                        "mutationReference", mutationReference,
                        "beforeStateReference", "before-" + runId,
                        "requestedBy", requestedBy,
                        "rationale", "Capture tenant A rollback anchor",
                        "tenantId", tenantA
                ),
                202
        );
        String rollbackAnchorId = rollbackAnchor.path("rollbackAnchorId").asText();
        assertFalse(rollbackAnchorId.isBlank());

        JsonNode structuralMapping = postJson(
                "/api/v1/admin/structural-publication-mapping/map",
                Map.of(
                        "publicationBatchId", "batch-" + runId,
                        "tenantId", tenantA,
                        "requestedBy", requestedBy,
                        "rationale", "Resolve structural publication references for tenant A",
                        "sourceMutationReferences", new String[]{mutationReference},
                        "draftReferences", new String[]{draftReference},
                        "includedStructuralScopes", new String[]{"concepts", "flows"},
                        "includedConcepts", new String[]{"Patient", "Appointment"}
                ),
                202
        );
        String structuralMappingId = structuralMapping.path("publicationMappingId").asText();
        assertEquals("RESOLVED", structuralMapping.path("integrityStatus").asText());
        assertTrue(arrayContains(structuralMapping.path("resolvedApprovalReferences"), approvalId));
        assertTrue(arrayContains(structuralMapping.path("resolvedAuditReferences"), auditId));
        assertTrue(arrayContains(structuralMapping.path("resolvedRollbackAnchorReferences"), rollbackAnchorId));

        JsonNode semanticMapping = postJson(
                "/api/v1/admin/semantic-publication-mapping",
                Map.of(
                        "mappingScope", "canonical-demo",
                        "mappingReference", "semantic-" + runId,
                        "draftReference", draftReference,
                        "semanticMutationReferences", new String[]{mutationReference},
                        "requestedBy", requestedBy,
                        "rationale", "Resolve semantic publication references for tenant A",
                        "tenantId", tenantA
                ),
                202
        );
        String semanticMappingId = semanticMapping.path("publicationMappingId").asText();
        assertEquals("RESOLVED", semanticMapping.path("integrityStatus").asText());
        assertTrue(arrayContains(semanticMapping.path("resolvedApprovalReferences"), approvalId));
        assertTrue(arrayContains(semanticMapping.path("resolvedAuditReferences"), auditId));
        assertTrue(arrayContains(semanticMapping.path("resolvedRollbackAnchorReferences"), rollbackAnchorId));

        JsonNode publicationTransaction = postJson(
                "/api/v1/admin/publication-transactions",
                Map.of(
                        "transactionScope", "canonical-demo",
                        "transactionReference", transactionReference,
                        "draftReference", draftReference,
                        "structuralMappingReferences", new String[]{structuralMappingId},
                        "semanticMappingReferences", new String[]{semanticMappingId},
                        "approvalReferences", new String[]{approvalId},
                        "rollbackAnchorReferences", new String[]{rollbackAnchorId},
                        "requestedBy", requestedBy,
                        "rationale", "Record tenant A publication transaction",
                        "tenantId", tenantA
                ),
                202
        );
        assertEquals("RESOLVED", publicationTransaction.path("integrityStatus").asText());
        assertEquals(tenantA, publicationTransaction.path("tenantId").asText());

        JsonNode blockedRollback = postJson(
                "/api/v1/admin/rollback-execution",
                Map.of(
                        "rollbackReference", rollbackReference,
                        "anchorReference", rollbackAnchorId,
                        "transactionReference", transactionReference,
                        "requestedBy", requestedBy,
                        "rationale", "Tenant B must not rollback tenant A references",
                        "tenantId", tenantB,
                        "rollbackMode", "rollback-execution-v1"
                ),
                202
        );

        String rollbackExecutionId = blockedRollback.path("rollbackExecutionId").asText();
        assertFalse(rollbackExecutionId.isBlank());
        assertEquals(tenantB, blockedRollback.path("tenantId").asText());
        assertEquals("VIOLATION", blockedRollback.path("tenantCompatibilityStatus").asText());
        assertEquals(2, blockedRollback.path("crossTenantViolationCount").asInt());
        assertEquals("CROSS_TENANT_REJECTED", blockedRollback.path("tenantIsolationStatus").asText());
        assertEquals("REVIEW_REQUIRED", blockedRollback.path("rollbackEligibilityStatus").asText());
        assertEquals("BLOCKED", blockedRollback.path("rollbackStatus").asText());
        assertEquals("BLOCKED_NO_ELIGIBLE_ANCHOR", blockedRollback.path("rollbackOutcome").asText());
        assertEquals("", blockedRollback.path("resolvedAnchorId").asText());
        assertEquals("", blockedRollback.path("resolvedTransactionId").asText());
        assertEquals("", blockedRollback.path("resolvedPublicationExecutionId").asText());
        assertTrue(arrayContains(blockedRollback.path("skippedScopes"), "canonical-source-registry"));
        assertTrue(arrayContains(blockedRollback.path("skippedScopes"), "regeneration-manifest"));
        assertTrue(arrayContainsSubstring(
                blockedRollback.path("rejectedReferenceReasons"),
                "publication transaction '" + transactionReference + "' belongs to tenant '" + tenantA + "'"
        ));
        assertTrue(arrayContainsSubstring(
                blockedRollback.path("rejectedReferenceReasons"),
                "rollback anchor '" + rollbackAnchorId + "' belongs to tenant '" + tenantA + "'"
        ));
        assertTrue(arrayContainsSubstring(
                blockedRollback.path("reviewRequiredActions"),
                "publication transaction '" + transactionReference + "' belongs to tenant '" + tenantA + "'"
        ));
        assertTrue(arrayContainsSubstring(
                blockedRollback.path("reviewRequiredActions"),
                "rollback anchor '" + rollbackAnchorId + "' belongs to tenant '" + tenantA + "'"
        ));

        JsonNode rollbackHistory = getJson("/api/v1/admin/rollback-execution/history", 200);
        JsonNode rollbackHistoryItem = findHistoryItem(
                rollbackHistory.path("items"),
                "rollbackExecutionId",
                rollbackExecutionId
        );
        assertNotNull(rollbackHistoryItem);
        assertEquals(tenantB, rollbackHistoryItem.path("tenantId").asText());
        assertEquals("VIOLATION", rollbackHistoryItem.path("tenantCompatibilityStatus").asText());
        assertEquals("CROSS_TENANT_REJECTED", rollbackHistoryItem.path("tenantIsolationStatus").asText());
    }

    private JsonNode postJson(String path, Object body, int expectedStatus) throws Exception {
        String response = mockMvc.perform(post(path)
                        .header("X-Api-Key", API_KEY)
                        .contentType(APPLICATION_JSON)
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
                        .contentType(APPLICATION_JSON))
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

    private static boolean arrayContainsSubstring(JsonNode items, String expectedValue) {
        if (!items.isArray()) {
            return false;
        }
        for (JsonNode item : items) {
            if (item.asText().contains(expectedValue)) {
                return true;
            }
        }
        return false;
    }
}

